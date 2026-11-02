using System.Collections.Concurrent;
using System.Globalization;
using System.Runtime.ExceptionServices;
using System.Text;
using System.Text.Json;

namespace WoZai.Next.Services;

/// <summary>An operation rejected by the kernel, with its localized public explanation.</summary>
public sealed class BridgeCommandException(string message) : Exception(message);

/// <summary>Owns a bounded JSONL connection and correlates replies independently of events.</summary>
internal sealed class JsonLineConnection : IAsyncDisposable
{
    internal const int MaximumFrameBytes = 2 * 1024 * 1024;
    internal static readonly JsonSerializerOptions Json = new() { PropertyNamingPolicy = JsonNamingPolicy.CamelCase };
    private static readonly UTF8Encoding Utf8 = new(false, true);
    private readonly Stream _input;
    private readonly Stream _output;
    private readonly TimeSpan _requestTimeout;
    private readonly int _maxLineBytes;
    private readonly int _maxPendingRequests;
    private readonly SemaphoreSlim _writeLock = new(1, 1);
    private readonly CancellationTokenSource _shutdown = new();
    private readonly ConcurrentDictionary<string, TaskCompletionSource<JsonElement>> _pending = new();
    private readonly object _gate = new();
    private Exception? _failure;
    private Task? _readTask;
    private long _nextId;

    public event Action<ClientState>? StateChanged;
    public event Action<string>? Notice;
    public event Action<Exception>? Disconnected;

    public JsonLineConnection(Stream input, Stream output, TimeSpan requestTimeout, int maxLineBytes = MaximumFrameBytes, int maxPendingRequests = 128)
    {
        ArgumentNullException.ThrowIfNull(input);
        ArgumentNullException.ThrowIfNull(output);
        if (requestTimeout <= TimeSpan.Zero) throw new ArgumentOutOfRangeException(nameof(requestTimeout));
        if (maxLineBytes < 1) throw new ArgumentOutOfRangeException(nameof(maxLineBytes));
        if (maxPendingRequests < 1) throw new ArgumentOutOfRangeException(nameof(maxPendingRequests));
        _maxPendingRequests = maxPendingRequests;
        _input = input;
        _output = output;
        _requestTimeout = requestTimeout;
        _maxLineBytes = maxLineBytes;
    }

    public void Start()
    {
        lock (_gate)
        {
            if (_readTask is not null) throw new InvalidOperationException("The kernel connection is already running.");
            ThrowIfFailed();
            _readTask = Task.Run(ReadLoopAsync);
        }
    }

    public async Task<T?> CallAsync<T>(string op, object? args = null, CancellationToken cancellationToken = default)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(op);
        cancellationToken.ThrowIfCancellationRequested();
        var id = Interlocked.Increment(ref _nextId).ToString(CultureInfo.InvariantCulture);
        var request = JsonSerializer.SerializeToUtf8Bytes(new { id, op, args = args ?? new { } }, Json);
        if (request.Length > _maxLineBytes) throw new InvalidDataException("The kernel request exceeds the 2 MiB frame limit.");
        var completion = new TaskCompletionSource<JsonElement>(TaskCreationOptions.RunContinuationsAsynchronously);
        lock (_gate)
        {
            ThrowIfFailed();
            if (_readTask is null) throw new InvalidOperationException("The kernel connection has not started.");
            if (_pending.Count >= _maxPendingRequests) throw new InvalidOperationException("Too many kernel operations are pending. Wait for the current operation to finish.");
            _pending.TryAdd(id, completion);
        }

        using var timeout = new CancellationTokenSource(_requestTimeout);
        using var linked = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken, timeout.Token, _shutdown.Token);
        var locked = false;
        var writing = false;
        try
        {
            await _writeLock.WaitAsync(linked.Token).ConfigureAwait(false);
            locked = true;
            linked.Token.ThrowIfCancellationRequested();
            ThrowIfFailed();
            // Cancellation partway through a frame makes the entire connection unusable.
            writing = true;
            await _output.WriteAsync(request, linked.Token).ConfigureAwait(false);
            await _output.WriteAsync(new byte[] { (byte)'\n' }, linked.Token).ConfigureAwait(false);
            await _output.FlushAsync(linked.Token).ConfigureAwait(false);
            writing = false;
            _writeLock.Release();
            locked = false;
            var data = await completion.Task.WaitAsync(linked.Token).ConfigureAwait(false);
            if (data.ValueKind == JsonValueKind.Null) return default;
            try { return data.Deserialize<T>(Json); }
            catch (JsonException) { throw new InvalidDataException("The kernel reply has an unexpected data type."); }
        }
        catch (OperationCanceledException)
        {
            if (writing) Fail(new IOException("Writing a kernel request was interrupted."));
            cancellationToken.ThrowIfCancellationRequested();
            if (timeout.IsCancellationRequested) throw new TimeoutException("The kernel operation timed out.");
            ThrowIfFailed();
            throw;
        }
        catch (IOException error)
        {
            Fail(error);
            ThrowIfFailed();
            throw;
        }
        catch (ObjectDisposedException)
        {
            ThrowIfFailed();
            throw;
        }
        finally
        {
            if (locked) _writeLock.Release();
            _pending.TryRemove(id, out _);
        }
    }

    private async Task ReadLoopAsync()
    {
        var buffer = new byte[Math.Min(8192, _maxLineBytes + 1)];
        using var line = new MemoryStream(Math.Min(4096, _maxLineBytes));
        try
        {
            while (true)
            {
                var count = await _input.ReadAsync(buffer, _shutdown.Token).ConfigureAwait(false);
                if (count == 0)
                    throw line.Length == 0
                        ? new EndOfStreamException("The kernel process closed its output.")
                        : new InvalidDataException("The kernel output ended inside a JSON frame.");
                var offset = 0;
                while (offset < count)
                {
                    var newline = Array.IndexOf(buffer, (byte)'\n', offset, count - offset);
                    var end = newline < 0 ? count : newline;
                    var length = end - offset;
                    if (line.Length + length > _maxLineBytes)
                        throw new InvalidDataException("The kernel output exceeds the 2 MiB frame limit.");
                    line.Write(buffer, offset, length);
                    if (newline < 0) break;
                    HandleFrame(Utf8.GetString(line.GetBuffer(), 0, checked((int)line.Length)));
                    line.SetLength(0);
                    offset = newline + 1;
                }
            }
        }
        catch (OperationCanceledException) when (_shutdown.IsCancellationRequested) { }
        catch (ObjectDisposedException) when (_shutdown.IsCancellationRequested) { }
        catch (JsonException) { Fail(new InvalidDataException("The kernel sent malformed JSON.")); }
        catch (DecoderFallbackException) { Fail(new InvalidDataException("The kernel output is not valid UTF-8.")); }
        catch (Exception error) { Fail(error); }
    }

    private void HandleFrame(string frame)
    {
        using var document = JsonDocument.Parse(frame);
        var root = document.RootElement;
        if (root.ValueKind != JsonValueKind.Object) throw InvalidFrame();
        if (root.TryGetProperty("event", out var eventName))
        {
            if (eventName.ValueKind != JsonValueKind.String || !root.TryGetProperty("data", out var data)) throw InvalidFrame();
            switch (eventName.GetString())
            {
                case "state":
                    if (data.ValueKind != JsonValueKind.Object) throw InvalidFrame();
                    var state = data.Deserialize<ClientState>(Json) ?? throw InvalidFrame();
                    Invoke(StateChanged, state);
                    break;
                case "notice":
                    if (data.ValueKind != JsonValueKind.String) throw InvalidFrame();
                    Invoke(Notice, data.GetString()!);
                    break;
            }
            return;
        }
        if (!root.TryGetProperty("id", out var id) || id.ValueKind != JsonValueKind.String ||
            !root.TryGetProperty("ok", out var ok) || ok.ValueKind is not (JsonValueKind.True or JsonValueKind.False)) throw InvalidFrame();
        JsonElement result = default;
        string? error = null;
        if (ok.GetBoolean())
        {
            if (!root.TryGetProperty("data", out result)) throw InvalidFrame();
        }
        else
        {
            if (!root.TryGetProperty("error", out var failure) || failure.ValueKind != JsonValueKind.String) throw InvalidFrame();
            error = failure.GetString();
        }
        // Late replies to timed-out/cancelled calls are valid and are discarded by ID.
        if (_pending.TryRemove(id.GetString()!, out var completion))
        {
            if (error is not null) completion.TrySetException(new BridgeCommandException(error));
            else completion.TrySetResult(result.Clone());
        }
    }

    private static InvalidDataException InvalidFrame() => new("The kernel sent an invalid protocol frame.");

    private void ThrowIfFailed()
    {
        var failure = Volatile.Read(ref _failure);
        if (failure is not null) ExceptionDispatchInfo.Capture(failure).Throw();
    }

    private void Fail(Exception error)
    {
        TaskCompletionSource<JsonElement>[] waiters;
        lock (_gate)
        {
            if (_failure is not null) return;
            _failure = error;
            waiters = _pending.Values.ToArray();
            _pending.Clear();
        }
        foreach (var waiter in waiters) waiter.TrySetException(error);
        _shutdown.Cancel();
        try { _input.Dispose(); } catch (IOException) { }
        if (!ReferenceEquals(_input, _output)) { try { _output.Dispose(); } catch (IOException) { } }
        Invoke(Disconnected, error);
    }

    internal static void Invoke<T>(Action<T>? handlers, T value)
    {
        if (handlers is null) return;
        foreach (Action<T> handler in handlers.GetInvocationList())
        {
            // UI callbacks must dispatch themselves; subscriber failures cannot corrupt framing.
            try { handler(value); } catch (Exception) { }
        }
    }

    public async ValueTask DisposeAsync()
    {
        Fail(new ObjectDisposedException(nameof(JsonLineConnection)));
        var reader = _readTask;
        if (reader is not null) await reader.ConfigureAwait(false);
    }
}
