using System.Diagnostics;
using System.Text;

namespace WoZai.Next.Services;

/// <summary>
/// Owns one private Java kernel child. Events run on a worker thread; WinUI callers must dispatch.
/// </summary>
public sealed class BridgeClient : IAsyncDisposable
{
    private static readonly UTF8Encoding Utf8 = new(false, true);
    private readonly Func<string, ProcessStartInfo> _startInfo;
    private readonly TimeSpan _requestTimeout;
    private readonly TimeSpan _stopTimeout;
    private readonly object _stateGate = new();
    private readonly SemaphoreSlim _lifecycle = new(1, 1);
    private JsonLineConnection? _connection;
    private Process? _process;
    private Task? _stderrTask;
    private ClientState? _current;
    private volatile bool _stopping;
    private bool _disposed;
    private int _failureReported;

    public event Action<ClientState>? StateChanged;
    public event Action<string>? Notice;
    public event Action<string>? Failed;
    public ClientState? Current => Volatile.Read(ref _current);

    public BridgeClient() : this(CreateStartInfo, TimeSpan.FromSeconds(45), TimeSpan.FromSeconds(3)) { }

    internal BridgeClient(Func<string, ProcessStartInfo> startInfo, TimeSpan requestTimeout, TimeSpan stopTimeout)
    {
        ArgumentNullException.ThrowIfNull(startInfo);
        if (requestTimeout <= TimeSpan.Zero) throw new ArgumentOutOfRangeException(nameof(requestTimeout));
        if (stopTimeout <= TimeSpan.Zero) throw new ArgumentOutOfRangeException(nameof(stopTimeout));
        _startInfo = startInfo;
        _requestTimeout = requestTimeout;
        _stopTimeout = stopTimeout;
    }

    public async Task StartAsync(string? profile = null)
    {
        await _lifecycle.WaitAsync().ConfigureAwait(false);
        try
        {
            ObjectDisposedException.ThrowIf(_disposed, this);
            if (_connection is not null && Current is not null) return;
            await CleanupAsync().ConfigureAwait(false);
            _stopping = false;
            Interlocked.Exchange(ref _failureReported, 0);
            try
            {
                var directory = Path.GetFullPath(profile ?? Environment.GetEnvironmentVariable("WOZAI_NEXT_DATA_DIR") ??
                    Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "WoZaiNext"));
                var start = _startInfo(directory);
                start.UseShellExecute = false;
                start.RedirectStandardInput = true;
                start.RedirectStandardOutput = true;
                start.RedirectStandardError = true;
                start.CreateNoWindow = true;
                start.StandardInputEncoding = Utf8;
                start.StandardOutputEncoding = Utf8;
                start.StandardErrorEncoding = Utf8;
                var process = new Process { StartInfo = start };
                _process = process;
                if (!process.Start()) throw new IOException("The communication kernel could not be launched.");
                var connection = new JsonLineConnection(process.StandardOutput.BaseStream, process.StandardInput.BaseStream, _requestTimeout);
                Volatile.Write(ref _connection, connection);
                connection.StateChanged += state => PublishState(connection, state);
                connection.Notice += message =>
                {
                    if (ReferenceEquals(Volatile.Read(ref _connection), connection) && !_stopping)
                        JsonLineConnection.Invoke(Notice, message);
                };
                connection.Disconnected += error =>
                {
                    if (ReferenceEquals(Volatile.Read(ref _connection), connection) && !_stopping)
                        ReportFailure("The communication kernel stopped. Restart the app to reconnect.");
                };
                // Stderr is diagnostic-only. Drain in fixed-size chunks without retaining or logging private data.
                _stderrTask = DrainDiagnosticsAsync(process.StandardError.BaseStream);
                connection.Start();
                var initial = await connection.CallAsync<ClientState>("snapshot").ConfigureAwait(false)
                    ?? throw new InvalidDataException("The communication kernel did not return its initial state.");
                // Publish the startup snapshot on a worker too, even when StartAsync was called on the UI thread.
                await Task.Run(() => PublishState(connection, initial)).ConfigureAwait(false);
            }
            catch (Exception error)
            {
                ReportFailure("The communication kernel could not start. Check the installation and try again.");
                await CleanupAsync().ConfigureAwait(false);
                throw new IOException("The communication kernel could not start. Check the installation and try again.", error);
            }
        }
        finally { _lifecycle.Release(); }
    }

    public Task<T?> CallAsync<T>(string op, object? args = null, CancellationToken cancellationToken = default)
    {
        var connection = Volatile.Read(ref _connection);
        if (_stopping || connection is null)
            return Task.FromException<T?>(new InvalidOperationException("The communication kernel is not running."));
        return connection.CallAsync<T>(op, args, cancellationToken);
    }

    public async Task CallAsync(string op, object? args = null, CancellationToken cancellationToken = default)
        => await CallAsync<object>(op, args, cancellationToken).ConfigureAwait(false);

    public async Task StopAsync()
    {
        await _lifecycle.WaitAsync().ConfigureAwait(false);
        try { await CleanupAsync().ConfigureAwait(false); }
        finally { _lifecycle.Release(); }
    }

    private void PublishState(JsonLineConnection connection, ClientState state)
    {
        lock (_stateGate)
        {
            if (_stopping || Volatile.Read(ref _failureReported) != 0 || !ReferenceEquals(Volatile.Read(ref _connection), connection)) return;
            Volatile.Write(ref _current, state);
        }
        JsonLineConnection.Invoke(StateChanged, state);
    }

    private void ReportFailure(string message)
    {
        lock (_stateGate)
        {
            Volatile.Write(ref _current, null);
            if (Interlocked.Exchange(ref _failureReported, 1) != 0) return;
        }
        JsonLineConnection.Invoke(Failed, message);
    }

    private async Task CleanupAsync()
    {
        _stopping = true;
        var connection = Interlocked.Exchange(ref _connection, null);
        var process = _process;
        _process = null;
        Volatile.Write(ref _current, null);
        if (connection is not null)
        {
            using var quitDeadline = new CancellationTokenSource(_stopTimeout);
            try { await connection.CallAsync<object>("quit", cancellationToken: quitDeadline.Token).ConfigureAwait(false); }
            catch (Exception) { /* Closing stdin and terminating our own child are the shutdown fallback. */ }
            await connection.DisposeAsync().ConfigureAwait(false);
        }
        if (process is not null)
        {
            try
            {
                if (!process.HasExited)
                {
                    using var exitDeadline = new CancellationTokenSource(_stopTimeout);
                    try { await process.WaitForExitAsync(exitDeadline.Token).ConfigureAwait(false); }
                    catch (OperationCanceledException)
                    {
                        // This Process is the instance we launched; never enumerate or terminate other Java processes.
                        process.Kill(entireProcessTree: true);
                        using var killDeadline = new CancellationTokenSource(_stopTimeout);
                        await process.WaitForExitAsync(killDeadline.Token).ConfigureAwait(false);
                    }
                }
            }
            catch (InvalidOperationException) { }
            finally { process.Dispose(); }
        }
        var stderr = _stderrTask;
        _stderrTask = null;
        if (stderr is not null)
        {
            try { await stderr.WaitAsync(_stopTimeout).ConfigureAwait(false); }
            catch (TimeoutException) { }
        }
    }

    private static async Task DrainDiagnosticsAsync(Stream error)
    {
        var buffer = new byte[4096];
        try { while (await error.ReadAsync(buffer).ConfigureAwait(false) != 0) { } }
        catch (IOException) { }
        catch (ObjectDisposedException) { }
    }

    private static ProcessStartInfo CreateStartInfo(string profile)
    {
        var root = AppContext.BaseDirectory;
        var java = Path.Combine(root, "kernel", "runtime", "bin", "java.exe");
        if (!File.Exists(java)) throw new FileNotFoundException("The bundled Java runtime is missing. Reinstall the app.");
        var library = Path.Combine(root, "kernel", "lib");
        var start = new ProcessStartInfo(java) { WorkingDirectory = root };
        start.ArgumentList.Add("-Djava.awt.headless=true");
        start.ArgumentList.Add($"-Dwozai.bluetooth.library={Path.Combine(library, "wozai_bluetooth.dll")}");
        start.ArgumentList.Add("-cp");
        start.ArgumentList.Add(Path.Combine(library, "*"));
        start.ArgumentList.Add("dev.ghost.wozai.next.BridgeMain");
        start.ArgumentList.Add("--profile");
        start.ArgumentList.Add(profile);
        return start;
    }

    public async ValueTask DisposeAsync()
    {
        await _lifecycle.WaitAsync().ConfigureAwait(false);
        try
        {
            if (_disposed) return;
            _disposed = true;
            await CleanupAsync().ConfigureAwait(false);
        }
        finally { _lifecycle.Release(); }
    }
}
