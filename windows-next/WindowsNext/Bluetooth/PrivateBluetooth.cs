using System.Net.Sockets;
using System.Runtime.InteropServices.WindowsRuntime;
using Windows.Devices.Bluetooth;
using Windows.Devices.Bluetooth.Advertisement;
using Windows.Devices.Bluetooth.GenericAttributeProfile;
using Windows.Foundation;
using WoZai.Next.Services;

namespace WoZai.Next.Bluetooth;

internal sealed record BlePeer(ulong Address, string Name);
internal sealed class NativeBleEndpoint { public int Port { get; set; } public string Token { get; set; } = ""; }

/// <summary>Windows central connects directly to Apple's foreground GATT peripheral.</summary>
internal sealed class PrivateBluetooth : IAsyncDisposable
{
    private readonly BridgeClient _kernel;
    private readonly Action _changed;
    private readonly object _gate = new();
    private readonly NativeOperationBudget _operations = new();
    private readonly Dictionary<ulong, BlePeer> _peers = new();
    private BluetoothLEAdvertisementWatcher? _watcher;
    private CancellationTokenSource? _link;
    private Task? _job;
    private bool _disposed;
    private bool _scanStarting;
    private long _scanEpoch;
    public bool Scanning => _watcher?.Status == BluetoothLEAdvertisementWatcherStatus.Started;
    public bool Busy => _link is not null;
    public BlePeer[] Peers { get { lock (_gate) return _peers.Values.ToArray(); } }
    public PrivateBluetooth(BridgeClient kernel, Action changed) { _kernel = kernel; _changed = changed; }

    public async Task StartScanAsync()
    {
        if (_disposed || _watcher is not null || _scanStarting) return;
        _scanStarting = true; long epoch = ++_scanEpoch;
        BluetoothAdapter? adapter;
        using var deadline = new CancellationTokenSource(TimeSpan.FromSeconds(10));
        try { adapter = await _operations.Run(() => BluetoothAdapter.GetDefaultAsync().AsTask(), deadline.Token); }
        finally { _scanStarting = false; }
        if (_disposed || epoch != _scanEpoch) return;
        if (adapter?.IsLowEnergySupported != true) throw new IOException("bleUnavailable");
        var watcher = new BluetoothLEAdvertisementWatcher { ScanningMode = BluetoothLEScanningMode.Active };
        watcher.AdvertisementFilter.Advertisement.ServiceUuids.Add(BleValueStream.Service);
        watcher.Received += (_, e) => {
            bool changed;
            lock (_gate)
            {
                if (!ReferenceEquals(_watcher, watcher) || _disposed || !e.Advertisement.ServiceUuids.Contains(BleValueStream.Service)) return;
                if (!_peers.ContainsKey(e.BluetoothAddress) && _peers.Count >= 32) return;
                var name = e.Advertisement.LocalName;
                if (string.IsNullOrWhiteSpace(name)) name = "NearbyIM";
                name = name[..Math.Min(name.Length, 64)];
                var peer = new BlePeer(e.BluetoothAddress, name);
                changed = !_peers.TryGetValue(peer.Address, out var previous) || previous != peer;
                _peers[peer.Address] = peer;
            }
            if (changed) _changed();
        };
        watcher.Stopped += (_, _) => {
            lock (_gate) if (ReferenceEquals(_watcher, watcher)) _watcher = null;
            _changed();
        };
        _watcher = watcher;
        try { watcher.Start(); } catch { _watcher = null; throw; }
        _changed();
    }
    public void StopScan()
    {
        _scanEpoch++;
        var watcher = _watcher; _watcher = null;
        if (watcher?.Status == BluetoothLEAdvertisementWatcherStatus.Started) watcher.Stop();
        lock (_gate) _peers.Clear(); _changed();
    }
    public Task ConnectAsync(ulong address)
    {
        if (_disposed || _link is not null) throw new IOException("bleBusy");
        lock (_gate) if (!_peers.ContainsKey(address)) throw new IOException("bleUnavailable");
        var lifetime = new CancellationTokenSource(); _link = lifetime;
        var job = LinkAsync(address, lifetime); _job = job; _changed(); return job;
    }
    private async Task LinkAsync(ulong address, CancellationTokenSource lifetime)
    {
        BluetoothLEDevice? device = null;
        GattDeviceService? service = null;
        GattSession? session = null;
        GattCharacteristic? rx = null, tx = null;
        BleValueStream? stream = null;
        TcpClient? local = null;
        NativeBleEndpoint? endpoint = null;
        TypedEventHandler<GattCharacteristic, GattValueChangedEventArgs>? values = null;
        TypedEventHandler<BluetoothLEDevice, object>? disconnected = null;
        using var closing = lifetime.Token.Register(() => { stream?.Dispose(); local?.Dispose(); });
        try
        {
            using var setup = CancellationTokenSource.CreateLinkedTokenSource(lifetime.Token);
            setup.CancelAfter(TimeSpan.FromSeconds(20));
            // Start on the UI thread: Windows may need to present Bluetooth access consent.
            device = await _operations.Run(() => BluetoothLEDevice.FromBluetoothAddressAsync(address).AsTask(), setup.Token, value => value?.Dispose());
            if (device is null) throw new IOException("bleUnavailable");
            var services = await _operations.Run(() => device.GetGattServicesForUuidAsync(BleValueStream.Service, BluetoothCacheMode.Uncached).AsTask(),
                setup.Token, value => { foreach (var unused in value.Services) unused.Dispose(); });
            if (services.Status != GattCommunicationStatus.Success || services.Services.Count != 1)
            { foreach (var unused in services.Services) unused.Dispose(); throw new IOException("bleUnavailable"); }
            service = services.Services[0];
            session = service.Session; session.MaintainConnection = true;
            var characteristics = await _operations.Run(() => service.GetCharacteristicsAsync(BluetoothCacheMode.Uncached).AsTask(), setup.Token);
            if (characteristics.Status != GattCommunicationStatus.Success) throw new IOException("bleUnavailable");
            rx = characteristics.Characteristics.SingleOrDefault(c => c.Uuid == BleValueStream.Receive);
            tx = characteristics.Characteristics.SingleOrDefault(c => c.Uuid == BleValueStream.Transmit);
            if (rx is null || tx is null || !rx.CharacteristicProperties.HasFlag(GattCharacteristicProperties.Write) ||
                !tx.CharacteristicProperties.HasFlag(GattCharacteristicProperties.Indicate)) throw new IOException("bleUnavailable");
            var transmit = rx;
            stream = new BleValueStream(Math.Max(20, service.Session.MaxPduSize - 3), async (value, token) => {
                using var deadline = CancellationTokenSource.CreateLinkedTokenSource(token);
                deadline.CancelAfter(TimeSpan.FromSeconds(10));
                var result = await _operations.Run(() => transmit.WriteValueWithResultAsync(value.AsBuffer(), GattWriteOption.WriteWithResponse).AsTask(), deadline.Token);
                if (result.Status != GattCommunicationStatus.Success) throw new IOException("bleLinkFailed");
            }, () => lifetime.Cancel());
            values = (_, e) => {
                if (e.CharacteristicValue.Length > 512) { stream.Dispose(); return; }
                stream.ReceiveValue(e.CharacteristicValue.ToArray());
            };
            tx.ValueChanged += values;
            disconnected = (sender, _) => { if (sender.ConnectionStatus == BluetoothConnectionStatus.Disconnected) lifetime.Cancel(); };
            device.ConnectionStatusChanged += disconnected;
            var subscribed = await _operations.Run(() => tx.WriteClientCharacteristicConfigurationDescriptorAsync(
                GattClientCharacteristicConfigurationDescriptorValue.Indicate).AsTask(), setup.Token);
            if (subscribed != GattCommunicationStatus.Success) throw new IOException("bleLinkFailed");
            endpoint = await _kernel.CallAsync<NativeBleEndpoint>("connectNativeBLE", cancellationToken: setup.Token);
            if (endpoint is null || endpoint.Port is < 1 or > 65535 || endpoint.Token.Length != 64) throw new IOException("bleLinkFailed");
            local = new TcpClient();
            await local.ConnectAsync("127.0.0.1", endpoint.Port, setup.Token);
            local.NoDelay = true;
            await local.GetStream().WriteAsync(Convert.FromHexString(endpoint.Token), setup.Token);
            var up = Pump(stream, local.GetStream(), lifetime.Token);
            var down = Pump(local.GetStream(), stream, lifetime.Token);
            await Task.WhenAny(up, down);
            lifetime.Cancel();
            try { await Task.WhenAll(up, down); } catch (Exception) { }
        }
        catch (Exception) when (lifetime.IsCancellationRequested) { }
        catch (Exception error) { throw new IOException("bleLinkFailed", error); }
        finally
        {
            lifetime.Cancel(); stream?.Dispose(); local?.Dispose();
            if (tx is not null && values is not null) tx.ValueChanged -= values;
            if (device is not null && disconnected is not null) device.ConnectionStatusChanged -= disconnected;
            if (tx is not null) {
                using var deadline = new CancellationTokenSource(TimeSpan.FromSeconds(2));
                try { await _operations.Run(() => tx.WriteClientCharacteristicConfigurationDescriptorAsync(GattClientCharacteristicConfigurationDescriptorValue.None).AsTask(), deadline.Token); } catch (Exception) { }
            }
            if (session is not null) { session.MaintainConnection = false; session.Dispose(); }
            service?.Dispose(); device?.Dispose();
            if (endpoint is not null) {
                using var deadline = new CancellationTokenSource(TimeSpan.FromSeconds(3));
                try { await _kernel.CallAsync("disconnectNativeBLE", new { token = endpoint.Token }, deadline.Token); } catch (Exception) { }
            }
            if (ReferenceEquals(_link, lifetime)) { _link = null; _job = null; }
            // Late WinRT completions can still hold this token; do not dispose a live CTS.
            _changed();
        }
    }
    private static async Task Pump(Stream source, Stream destination, CancellationToken token)
    {
        var buffer = new byte[4096];
        while (true) { int count = await source.ReadAsync(buffer, token); if (count == 0) return; await destination.WriteAsync(buffer.AsMemory(0, count), token); }
    }
    public async Task DisconnectAsync()
    {
        _link?.Cancel(); if (_job is { } job) try { await job; } catch (Exception) { }
    }
    public async ValueTask DisposeAsync() { _disposed = true; StopScan(); await DisconnectAsync(); }
}
