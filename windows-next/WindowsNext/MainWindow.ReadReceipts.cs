using Microsoft.UI.Xaml;
using System.Runtime.InteropServices;
using System.Text;
using Windows.Foundation;

namespace WoZai.Next;

public sealed partial class MainWindow
{
    private sealed class ReadProgress
    {
        public DateTimeOffset Since;
        public double Top, Bottom;
        public List<(double Top, double Bottom)> Covered = new();
    }

    private void ResetDwell()
    {
        foreach (var progress in _readProgress.Values) progress.Since = default;
    }

    // A selected conversation is never evidence of reading. Every visible body segment
    // must dwell while this real desktop window owns the unlocked foreground.
    private async void ObserveVisibleBodies()
    {
        if (_observing || _modalDepth > 0 || _tab != "chats" || !_state.ReadReceipts || !_state.CanObserve || !_active || _messages is null || string.IsNullOrEmpty(SelectedPeer) || GetForegroundWindow() != _hwnd || IsIconic(_hwnd) || !DesktopUnlocked())
        { ResetDwell(); return; }
        var peer = SelectedPeer;
        var now = DateTimeOffset.UtcNow;
        var ready = new List<string>();
        foreach (var (id, body) in _incomingBodies)
        {
            var key = peer + ":" + id;
            if (_observed.Contains(key) || body.ActualHeight <= 0 || body.Visibility != Visibility.Visible) continue;
            Rect rect;
            try { rect = body.TransformToVisual(_messages).TransformBounds(new Rect(0, 0, body.ActualWidth, body.ActualHeight)); }
            catch { continue; }
            if (!_readProgress.TryGetValue(key, out var progress)) _readProgress[key] = progress = new();
            var top = Math.Max(0, -rect.Top);
            var bottom = Math.Min(body.ActualHeight, _messages.ViewportHeight - rect.Top);
            if (rect.Right <= 0 || rect.Left >= _messages.ViewportWidth || bottom <= top)
            { progress.Since = default; continue; }
            if (progress.Since == default || Math.Abs(top - progress.Top) > 3 || Math.Abs(bottom - progress.Bottom) > 3)
            { progress.Top = top; progress.Bottom = bottom; progress.Since = now; continue; }
            if ((now - progress.Since).TotalMilliseconds < 900) continue;
            progress.Covered.Add((top, bottom));
            var covered = 0d;
            foreach (var part in progress.Covered.OrderBy(p => p.Top))
            {
                if (part.Top > covered + 3) break;
                covered = Math.Max(covered, part.Bottom);
            }
            if (covered >= body.ActualHeight - 3) ready.Add(id);
            if (ready.Count >= 32) break;
        }
        if (ready.Count == 0) return;
        _observing = true;
        try
        {
            // Check again immediately before crossing the process boundary.
            if (_active && _tab == "chats" && SelectedPeer == peer && GetForegroundWindow() == _hwnd && DesktopUnlocked())
            {
                await Call("observed", new { peerId = peer, ids = ready });
                foreach (var id in ready) _observed.Add(peer + ":" + id);
            }
        }
        catch { ResetDwell(); }
        finally { _observing = false; }
    }

    private static bool DesktopUnlocked()
    {
        var desktop = OpenInputDesktop(0, false, 0x0001);
        if (desktop == 0) return false;
        try
        {
            var name = new StringBuilder(256);
            return GetUserObjectInformation(desktop, 2, name, name.Capacity * 2, out _) && string.Equals(name.ToString(), "Default", StringComparison.OrdinalIgnoreCase);
        }
        finally { CloseDesktop(desktop); }
    }
    [DllImport("user32.dll")] private static extern nint GetForegroundWindow();
    [DllImport("user32.dll")] [return: MarshalAs(UnmanagedType.Bool)] private static extern bool IsIconic(nint hWnd);
    [DllImport("user32.dll", SetLastError = true)] private static extern nint OpenInputDesktop(uint flags, [MarshalAs(UnmanagedType.Bool)] bool inherit, uint desiredAccess);
    [DllImport("user32.dll", CharSet = CharSet.Unicode, SetLastError = true)] [return: MarshalAs(UnmanagedType.Bool)] private static extern bool GetUserObjectInformation(nint handle, int index, StringBuilder information, int length, out int needed);
    [DllImport("user32.dll")] [return: MarshalAs(UnmanagedType.Bool)] private static extern bool CloseDesktop(nint desktop);
}
