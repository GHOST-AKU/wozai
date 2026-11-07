using System.Net.Sockets;
using System.Security.Cryptography;
using Windows.Devices.Bluetooth;
using Windows.Devices.Bluetooth.Rfcomm;
using Windows.Devices.Enumeration;
using Windows.Networking.Sockets;

namespace WoZai.Next.Mesh;

/// <summary>System Bluetooth only. No USB-HCI driver, Python, address editor or LAN fallback.</summary>
internal sealed class BluetoothMesh : IAsyncDisposable
{
    private static readonly SemaphoreSlim Capacity = new(4,4);
    private readonly Action<Action> _dispatch;
    private readonly Action<string> _notice;
    private readonly int _port;
    private readonly string _label = "-" + Convert.ToHexString(RandomNumberGenerator.GetBytes(3)).ToLowerInvariant();
    private readonly CancellationTokenSource _stop = new();
    private readonly Dictionary<string, DateTimeOffset> _candidates = new();
    private readonly HashSet<StreamSocket> _sockets = new();
    private readonly HashSet<Task> _jobs = new();
    private DeviceWatcher? _watcher;
    private StreamSocketListener? _listener;
    private RfcommServiceProvider? _provider;
    private int _slots, _callbacks;
    private readonly HashSet<string> _inFlight = new();
    public BluetoothMesh(int port, Action<Action> dispatch, Action<string> notice) { _port = port; _dispatch = dispatch; _notice = notice; }
    public async Task StartAsync()
    {
        var adapter = await BluetoothAdapter.GetDefaultAsync().AsTask(_stop.Token);
        if (adapter is null || !adapter.IsClassicSupported) throw new IOException("bluetooth_unavailable");
        _provider = await RfcommServiceProvider.CreateAsync(RfcommServiceId.FromUuid(MeshWire.Service)).AsTask(_stop.Token);
        _listener = new StreamSocketListener();
        _listener.ConnectionReceived += (_, e) => Queue(() => Admit(e.Socket), e.Socket.Dispose);
        await _listener.BindServiceNameAsync(_provider.ServiceId.AsString(), SocketProtectionLevel.PlainSocket).AsTask(_stop.Token);
        _stop.Token.ThrowIfCancellationRequested();
        _provider.StartAdvertising(_listener, true);
        _watcher = DeviceInformation.CreateWatcher(BluetoothDevice.GetDeviceSelectorFromPairingState(false), null, DeviceInformationKind.AssociationEndpoint);
        _watcher.Added += (_, device) => Queue(() => Observe(device.Id));
        _watcher.Updated += (_, device) => Queue(() => Observe(device.Id));
        _watcher.Start();
        // Also query paired devices. Unpaired watcher initiates the actual nearby inquiry.
        var known = await DeviceInformation.FindAllAsync(BluetoothDevice.GetDeviceSelectorFromPairingState(true)).AsTask(_stop.Token);
        foreach (var item in known.Take(16)) Observe(item.Id);
        Track(RetryAsync());
    }
    private void Queue(Action action, Action? rejected = null)
    {
        if (Interlocked.Increment(ref _callbacks) > 32 || _stop.IsCancellationRequested) {
            Interlocked.Decrement(ref _callbacks); rejected?.Invoke(); return;
        }
        _dispatch(() => { try { action(); } finally { Interlocked.Decrement(ref _callbacks); } });
    }
    private async Task RetryAsync()
    {
        try {
            using var timer = new PeriodicTimer(TimeSpan.FromSeconds(5));
            while (await timer.WaitForNextTickAsync(_stop.Token))
                foreach (var id in _candidates.Keys.ToArray()) Observe(id);
        } catch (OperationCanceledException) { }
    }
    public void RequestStop() { _stop.Cancel(); }
    private void Track(Task job)
    {
        _jobs.Add(job);
        _ = job.ContinueWith(_ => _dispatch(() => _jobs.Remove(job)), TaskScheduler.Default);
    }
    private void Observe(string id)
    {
        if (_stop.IsCancellationRequested || _slots >= 4 || _inFlight.Contains(id)) return;
        var now = DateTimeOffset.UtcNow;
        if (_candidates.TryGetValue(id, out var retry) && now < retry) return;
        if (!_candidates.ContainsKey(id) && _candidates.Count >= 16) {
            var victim = _candidates.Where(p => !_inFlight.Contains(p.Key)).OrderBy(p => p.Value).FirstOrDefault();
            if (victim.Key is null) return;
            _candidates.Remove(victim.Key);
        }
        _candidates[id] = now.AddSeconds(60);
        if (!Capacity.Wait(0)) { _candidates[id] = now.AddSeconds(5); return; }
        _slots++; _inFlight.Add(id);
        Track(DialAsync(id));
    }
    private async Task DialAsync(string id)
    {
        StreamSocket? socket = null;
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(_stop.Token);
        deadline.CancelAfter(TimeSpan.FromSeconds(15));
        try
        {
            using var device = await BluetoothDevice.FromIdAsync(id).AsTask(deadline.Token);
            if (device is null) return;
            var services = await device.GetRfcommServicesForIdAsync(RfcommServiceId.FromUuid(MeshWire.Service), BluetoothCacheMode.Uncached).AsTask(deadline.Token);
            if (services.Error != BluetoothError.Success || services.Services.Count == 0) return;
            using var service = services.Services[0];
            socket = new StreamSocket(); _sockets.Add(socket);
            await socket.ConnectAsync(service.ConnectionHostName, service.ConnectionServiceName, SocketProtectionLevel.PlainSocket).AsTask(deadline.Token);
            var owned = socket; socket = null;
            await LinkAsync(owned, true);
        }
        catch (Exception) { /* Discovery is untrusted and radio exceptions may contain private addresses. */ }
        finally
        {
            if (socket is not null) { _sockets.Remove(socket); socket.Dispose(); }
            // Pending ownership is distinct from the admitted stream slot.
            _slots--; _inFlight.Remove(id); Capacity.Release();
        }
    }
    private void Admit(StreamSocket socket)
    {
        if (_stop.IsCancellationRequested || _slots >= 4 || !Capacity.Wait(0)) { socket.Dispose(); return; }
        _sockets.Add(socket); _slots++; Track(IncomingAsync(socket));
    }
    private async Task IncomingAsync(StreamSocket socket)
    {
        try { await LinkAsync(socket, false); } finally { _slots--; Capacity.Release(); }
    }
    private async Task LinkAsync(StreamSocket socket, bool outbound)
    {
        using var lifetime = CancellationTokenSource.CreateLinkedTokenSource(_stop.Token);
        using var closing = lifetime.Token.Register(socket.Dispose);
        try
        {
            using var input = socket.InputStream.AsStreamForRead();
            using var output = socket.OutputStream.AsStreamForWrite();
            using (var setup = CancellationTokenSource.CreateLinkedTokenSource(lifetime.Token))
            {
                setup.CancelAfter(TimeSpan.FromSeconds(15));
                using var setupClose = setup.Token.Register(socket.Dispose);
                await MeshWire.HandshakeAsync(input, output, _label, outbound, setup.Token);
            }
            using var core = new TcpClient();
            await core.ConnectAsync("127.0.0.1", _port, lifetime.Token).AsTask().WaitAsync(TimeSpan.FromSeconds(3), lifetime.Token);
            core.NoDelay = true;
            var up = MeshWire.PumpAsync(input, core.GetStream(), lifetime.Token);
            var down = MeshWire.PumpAsync(core.GetStream(), output, lifetime.Token);
            await Task.WhenAny(up, down);
            lifetime.Cancel(); core.Dispose();
            try { await Task.WhenAll(up, down); } catch (Exception) { }
        }
        catch (Exception) { /* The Core supplies authenticated neighbor status to the UI. */ }
        finally { socket.Dispose(); _sockets.Remove(socket); }
    }
    public async ValueTask DisposeAsync()
    {
        _stop.Cancel();
        try { if (_watcher?.Status is DeviceWatcherStatus.Started or DeviceWatcherStatus.EnumerationCompleted) _watcher.Stop(); } catch (Exception) { }
        try { _provider?.StopAdvertising(); } catch (Exception) { }
        try { _listener?.Dispose(); } catch (Exception) { }
        foreach (var socket in _sockets.ToArray()) try { socket.Dispose(); } catch (Exception) { }
        try { await Task.WhenAll(_jobs.ToArray()).WaitAsync(TimeSpan.FromSeconds(5)); } catch (Exception) { _notice("meshCleanupIncomplete"); }
        _sockets.Clear(); _candidates.Clear();
    }
}
