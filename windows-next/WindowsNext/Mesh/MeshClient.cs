using System.Diagnostics;
using System.Net;
using System.Security.AccessControl;
using System.Security.Principal;
using System.Text;
using System.Text.Json;

namespace WoZai.Next.Mesh;

internal sealed class MeshClient : IAsyncDisposable
{
    private readonly Action<Action> _dispatch;
    private readonly SemaphoreSlim _commands = new(1, 1), _events = new(128, 128), _lifecycle = new(1, 1);
    private Process? _core;
    private Microsoft.Win32.SafeHandles.SafeFileHandle? _job;
    private BluetoothMesh? _radio;
    private CancellationTokenSource? _stop;
    private Task? _reader, _stderr;
    private TaskCompletionSource<int>? _ready;
    private long _generation;
    private readonly HashSet<string> _neighbors = new();
    private readonly List<MeshMessage> _messages = new();
    private readonly Dictionary<string, PendingSend> _requests = new();
    public IReadOnlyList<MeshMessage> Messages => _messages;
    public int Neighbors => _neighbors.Count;
    public bool Running { get; private set; }
    public bool Starting { get; private set; }
    public bool Relay { get; private set; }
    public string Error { get; private set; } = "";
    public event Action? Changed;
    public MeshClient(Action<Action> dispatch) { _dispatch = dispatch; }

    private static string PrivateState()
    {
        var root = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "WoZaiNext", "Mesh");
        var sid = WindowsIdentity.GetCurrent().User ?? throw new IOException("identity_unavailable");
        var info = new DirectoryInfo(root);
        if (info.Exists)
        {
            if ((info.Attributes & FileAttributes.ReparsePoint) != 0) throw new IOException("state_redirect");
            var access = info.GetAccessControl();
            if (!access.AreAccessRulesProtected || !access.GetOwner(typeof(SecurityIdentifier)).Equals(sid)) throw new IOException("state_not_private");
            foreach (FileSystemAccessRule rule in access.GetAccessRules(true, true, typeof(SecurityIdentifier)))
                if (rule.AccessControlType == AccessControlType.Allow && !rule.IdentityReference.Equals(sid)) throw new IOException("state_not_private");
        }
        else
        {
            Directory.CreateDirectory(root);
            var access = new DirectorySecurity(); access.SetOwner(sid); access.SetAccessRuleProtection(true, false);
            access.AddAccessRule(new FileSystemAccessRule(sid, FileSystemRights.FullControl,
                InheritanceFlags.ContainerInherit | InheritanceFlags.ObjectInherit, PropagationFlags.None, AccessControlType.Allow));
            new DirectoryInfo(root).SetAccessControl(access);
        }
        return root;
    }
    public async Task StartAsync()
    {
        await _lifecycle.WaitAsync();
        try
        {
            if (Running || Starting) return;
            await CleanupAsync();
            var token = ++_generation;
            Starting = true; Error = ""; Changed?.Invoke();
            _stop = new CancellationTokenSource();
            _ready = new(TaskCreationOptions.RunContinuationsAsynchronously);
            var start = new ProcessStartInfo(Path.Combine(AppContext.BaseDirectory, "kernel", "mesh-lab.exe")) {
                UseShellExecute = false, CreateNoWindow = true, RedirectStandardInput = true,
                RedirectStandardOutput = true, RedirectStandardError = true,
                StandardInputEncoding = new UTF8Encoding(false, true), StandardOutputEncoding = new UTF8Encoding(false, true)
            };
            start.ArgumentList.Add("run"); start.ArgumentList.Add("--state"); start.ArgumentList.Add(PrivateState());
            start.ArgumentList.Add("--listen"); start.ArgumentList.Add("127.0.0.1:0");
            _core = Process.Start(start) ?? throw new IOException("core_start_failed");
            _job = CoreJob.Attach(_core);
            _reader = ReadAsync(_core.StandardOutput, token, _stop.Token);
            _stderr = DrainAsync(_core.StandardError, _stop.Token);
            int port = await _ready.Task.WaitAsync(TimeSpan.FromSeconds(10), _stop.Token);
            _radio = new BluetoothMesh(port, _dispatch, key => { Error = key; Changed?.Invoke(); });
            await _radio.StartAsync().WaitAsync(TimeSpan.FromSeconds(20), _stop.Token);
            Running = true; Starting = false; Changed?.Invoke();
        }
        catch (Exception) { await CleanupAsync(); Starting = false; Error = "meshUnavailable"; Changed?.Invoke(); }
        finally { _lifecycle.Release(); }
    }
    private async Task ReadAsync(StreamReader reader, long generation, CancellationToken stop)
    {
        var ready = _ready;
        try
        {
            var buffer = new char[512]; var line = new StringBuilder();
            while (true)
            {
                int count = await reader.ReadAsync(buffer.AsMemory(), stop).ConfigureAwait(false);
                if (count == 0) break;
                for (int i = 0; i < count; i++)
                {
                    if (buffer[i] != '\n') { if (line.Length >= 16384) throw new IOException("event_bound"); line.Append(buffer[i]); continue; }
                    using var json = JsonDocument.Parse(line.ToString(), new JsonDocumentOptions { MaxDepth = 32 }); line.Clear();
                    var value = json.RootElement.Clone();
                    if (value.GetProperty("event").GetString() == "ready")
                    {
                        var endpoint = IPEndPoint.Parse(value.GetProperty("address").GetString()!);
                        if (!IPAddress.IsLoopback(endpoint.Address)) throw new IOException("core_endpoint");
                        ready?.TrySetResult(endpoint.Port);
                    }
                    await _events.WaitAsync(stop).ConfigureAwait(false);
                    _dispatch(() => { try { if (_generation == generation) Apply(value); } finally { _events.Release(); } });
                }
            }
            if (!stop.IsCancellationRequested) throw new IOException("core_ended");
        }
        catch (Exception) {
            ready?.TrySetException(new IOException("core_failed"));
            if (!stop.IsCancellationRequested) _dispatch(() => { if (_generation == generation) _ = StopAsync("meshCoreStopped"); });
        }
    }
    private static async Task DrainAsync(StreamReader reader, CancellationToken stop)
    {
        var buffer = new char[512];
        try { while (await reader.ReadAsync(buffer.AsMemory(), stop).ConfigureAwait(false) != 0) { } } catch (Exception) { }
    }
    private void Apply(JsonElement value)
    {
        string kind = value.GetProperty("event").GetString()!;
        string Get(string key) => value.TryGetProperty(key, out var item) && item.ValueKind == JsonValueKind.String ? item.GetString()! : "";
        switch (kind)
        {
            case "neighbor_up": _neighbors.Add(Get("peer")); break;
            case "neighbor_down": _neighbors.Remove(Get("peer")); break;
            case "queued":
                var request = Get("request");
                if (_requests.Remove(request, out var pending)) { _messages.Add(new(Get("id"), pending.Body, true, "meshQueued")); pending.Accepted.TrySetResult(true); }
                break;
            case "message":
                if (_messages.All(m => m.Id != Get("id"))) _messages.Add(new(Get("id"), Get("body"), false, "meshReceived"));
                break;
            case "handed": Update(Get("id"), "meshHanded"); break;
            case "expired": Update(Get("id"), "meshExpired"); break;
            case "error": FailRequests(); Error = "meshSendFailed"; break;
            case "relay": Relay = value.GetProperty("enabled").GetBoolean(); break;
            default: return;
        }
        if (_messages.Count > 200) _messages.RemoveRange(0, _messages.Count - 200);
        Changed?.Invoke();
    }
    private void Update(string id, string state) { int index = _messages.FindIndex(m => m.Id == id); if (index >= 0) _messages[index] = _messages[index] with { State = state }; }
    private async Task CommandAsync(object command)
    {
        if (!await _commands.WaitAsync(0)) throw new IOException("command_busy");
        try
        {
            var core = _core ?? throw new IOException("core_stopped");
            await core.StandardInput.WriteLineAsync(JsonSerializer.Serialize(command)).WaitAsync(TimeSpan.FromSeconds(3));
            await core.StandardInput.FlushAsync().WaitAsync(TimeSpan.FromSeconds(3));
        }
        finally { _commands.Release(); }
    }
    public async Task SendAsync(string text)
    {
        if (!Running || string.IsNullOrWhiteSpace(text) || Encoding.UTF8.GetByteCount(text) > 2048 || _requests.Count >= 4) throw new IOException("send_bound");
        var request = Guid.NewGuid().ToString("N"); var pending = new PendingSend(text); _requests.Add(request, pending);
        try { await CommandAsync(new { cmd = "send", text, ttl = 60, hops = 4, request }); await pending.Accepted.Task.WaitAsync(TimeSpan.FromSeconds(5)); }
        catch { _requests.Remove(request); throw; }
    }
    public async Task RelayAsync(bool enabled) { await CommandAsync(new { cmd = "relay", enabled }); }
    public async Task StopAsync(string error = "")
    {
        _stop?.Cancel(); _radio?.RequestStop();
        await _lifecycle.WaitAsync();
        try { ++_generation; await CleanupAsync(); Error = error; Changed?.Invoke(); } finally { _lifecycle.Release(); }
    }
    private void FailRequests() {
        foreach (var request in _requests.Values) request.Accepted.TrySetException(new IOException("send_unconfirmed"));
        _requests.Clear();
    }
    private sealed record PendingSend(string Body) {
        public TaskCompletionSource<bool> Accepted { get; } = new(TaskCreationOptions.RunContinuationsAsynchronously);
    }
    private async Task CleanupAsync()
    {
        ++_generation;
        _stop?.Cancel();
        if (_radio is not null) { await _radio.DisposeAsync(); _radio = null; }
        if (_core is not null)
        {
            try { _core.StandardInput.Close(); await _core.WaitForExitAsync().WaitAsync(TimeSpan.FromSeconds(3)); }
            catch { try { _core.Kill(true); await _core.WaitForExitAsync().WaitAsync(TimeSpan.FromSeconds(3)); } catch { } }
            _core.Dispose(); _core = null;
        }
        _job?.Dispose(); _job = null;
        if (_reader is not null) try { await _reader.WaitAsync(TimeSpan.FromSeconds(2)); } catch { }
        if (_stderr is not null) try { await _stderr.WaitAsync(TimeSpan.FromSeconds(2)); } catch { }
        _reader = _stderr = null; _stop?.Dispose(); _stop = null;
        _neighbors.Clear(); FailRequests(); Running = Starting = Relay = false;
        for (int i=0;i<_messages.Count;i++) if (_messages[i].Own && _messages[i].State is "meshQueued" or "meshHanded") _messages[i]=_messages[i] with { State="meshStopped" };
    }
    public async ValueTask DisposeAsync() { await StopAsync(); }
}
internal sealed record MeshMessage(string Id, string Body, bool Own, string State);
