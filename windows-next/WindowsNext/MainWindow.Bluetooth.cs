using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using WoZai.Next.Bluetooth;

namespace WoZai.Next;

public sealed partial class MainWindow
{
    private PrivateBluetooth? _privateBLE;
    private int _bleRefreshQueued;
    private PrivateBluetooth PrivateBLE => _privateBLE ??= new PrivateBluetooth(_bridge, () => {
        // Coalesce discovery callbacks instead of enqueuing one UI rebuild per advertisement.
        if (Interlocked.Exchange(ref _bleRefreshQueued, 1) != 0) return;
        if (!DispatcherQueue.TryEnqueue(() => {
            Volatile.Write(ref _bleRefreshQueued, 0);
            if (!_closed && _tab == "nearby") RenderNearby();
        })) Volatile.Write(ref _bleRefreshQueued, 0);
    });

    private void AddPrivateBluetooth(StackPanel body)
    {
        var panel = Stack(12); panel.Children.Add(Heading(T("blePrivate"), 17));
        panel.Children.Add(Text(T("blePrivateBody"), 13, true));
        bool scanning = _privateBLE?.Scanning == true;
        var search = Button(T(scanning ? "stopSearch" : "searchDevices"), () => Run(async () => {
            if (scanning) PrivateBLE.StopScan();
            else { try { await PrivateBLE.StartScanAsync(); } catch { ShowNotice(T("bleUnavailable")); } }
            RenderNearby();
        }));
        panel.Children.Add(search);
        if (_privateBLE?.Busy == true) panel.Children.Add(Button(T("disconnect"), () => Run(async () => { await PrivateBLE.DisconnectAsync(); })));
        foreach (var peer in _privateBLE?.Peers ?? [])
        {
            var row = Row(); row.Children.Add(Text(peer.Name, 15));
            var connect = Button(T("connect"), () => Run(async () => {
                if (_state.Phase != "idle") { ShowNotice(T("bleBusy")); return; }
                try { await PrivateBLE.ConnectAsync(peer.Address); } catch { ShowNotice(T("bleLinkFailed")); }
            }));
            connect.IsEnabled = _privateBLE?.Busy != true && _state.Phase == "idle";
            row.Children.Add(connect); panel.Children.Add(row);
        }
        body.Children.Add(Card(panel));
    }
}
