using Microsoft.UI;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using System.Diagnostics;
using System.Runtime.InteropServices;
using WoZai.Next.Services;
using Windows.Foundation;
using Windows.System;

namespace WoZai.Next;

public sealed partial class MainWindow : Window
{
    private readonly BridgeClient _bridge = new();
    private ClientState _state = new();
    private readonly Grid _root = new();
    private readonly NavigationView _nav = new();
    private readonly Grid _page = new();
    private readonly InfoBar _notice = new() { IsClosable = true };
    private readonly TextBlock _titleStatus = new();
    private readonly DispatcherTimer _draftTimer = new() { Interval = TimeSpan.FromMilliseconds(350) };
    private readonly DispatcherTimer _readTimer = new() { Interval = TimeSpan.FromMilliseconds(300) };
    private readonly SemaphoreSlim _dialogGate = new(1, 1);
    private readonly nint _hwnd;
    private string _tab = "chats";
    private bool _loaded, _closed, _active, _renderingPeers, _applyingDraft, _draftDirty, _sending;
    private long _draftRevision;
    private string? _composerPeer, _cachedDraftPeer;
    private string _cachedDraftText = "";
    private bool _cachedDraftDirty;
    private long _cachedDraftRevision;
    private string _appearance = "", _peerQuery = "", _messageQuery = "", _addressText = "";
    private TextBox? _composer;
    private Button? _send;
    private ListView? _peers;
    private ScrollViewer? _messages;
    private StackPanel? _messageStack;
    private Grid? _chatPanel;
    private TextBlock? _chatTitle, _chatSubtitle;
    private TextBlock? _characterCount;
    private StackPanel? _chatActions;
    private Button? _attachFileButton, _attachPhotoButton, _reconnectButton;
    private string _renderedMessages = "";
    private string _peerSignature = "";
    private readonly Dictionary<string, TextBlock> _incomingBodies = new();
    private readonly Dictionary<string, ReadProgress> _readProgress = new();
    private readonly HashSet<string> _observed = new();
    private bool _observing;
    private int _modalDepth;
    private string? _approvalShown;

    public MainWindow()
    {
        Title = "我在next";
        _hwnd = WinRT.Interop.WindowNative.GetWindowHandle(this);
        AppWindow.Resize(new Windows.Graphics.SizeInt32(1220, 820));
        SystemBackdrop = new MicaBackdrop();
        ExtendsContentIntoTitleBar = true;
        AutomationProperties.SetAutomationId(_root, "MainRoot");
        _root.RowDefinitions.Add(new() { Height = new GridLength(48) });
        _root.RowDefinitions.Add(new() { Height = GridLength.Auto });
        _root.RowDefinitions.Add(new() { Height = new GridLength(1, GridUnitType.Star) });
        Content = _root;
        App.StartupTrace("Window content attached");

        var titleBar = new Grid { Padding = new Thickness(22, 0, 150, 0) };
        titleBar.ColumnDefinitions.Add(new() { Width = GridLength.Auto });
        titleBar.ColumnDefinitions.Add(new() { Width = new GridLength(1, GridUnitType.Star) });
        var brand = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 10, VerticalAlignment = VerticalAlignment.Center };
        brand.Children.Add(new FontIcon { Glyph = "\uE8F2", FontSize = 18, Foreground = AccentBrush() });
        brand.Children.Add(new TextBlock { Text = "我在", FontSize = 16, FontWeight = Microsoft.UI.Text.FontWeights.SemiBold });
        brand.Children.Add(new TextBlock { Text = "next", FontSize = 12, Opacity = .58, VerticalAlignment = VerticalAlignment.Center });
        titleBar.Children.Add(brand);
        _titleStatus.FontSize = 12;
        _titleStatus.Opacity = .6;
        _titleStatus.HorizontalAlignment = HorizontalAlignment.Right;
        _titleStatus.VerticalAlignment = VerticalAlignment.Center;
        Grid.SetColumn(_titleStatus, 1);
        titleBar.Children.Add(_titleStatus);
        _root.Children.Add(titleBar);
        SetTitleBar(titleBar);
        _notice.Margin = new Thickness(76, 0, 16, 8);
        Grid.SetRow(_notice, 1);
        _root.Children.Add(_notice);

        _nav.PaneDisplayMode = NavigationViewPaneDisplayMode.LeftCompact;
        _nav.IsPaneToggleButtonVisible = false;
        _nav.IsBackButtonVisible = NavigationViewBackButtonVisible.Collapsed;
        _nav.IsSettingsVisible = false;
        // WinUI needs the NavigationView's SplitView template before changing pane state.
        _nav.Loaded += (_, _) => DispatcherQueue.TryEnqueue(Microsoft.UI.Dispatching.DispatcherQueuePriority.Low, () => {
            App.StartupTrace("Navigation loaded; applying template");
            _nav.ApplyTemplate(); App.StartupTrace("Navigation template applied"); _nav.UpdateLayout(); App.StartupTrace("Navigation layout updated"); _nav.IsPaneOpen = false;
            App.StartupTrace("Navigation closed");
            if(_nav.SelectedItem is null) _nav.SelectedItem = _nav.MenuItems[0];
            App.StartupTrace("Navigation selected");
        });
        _nav.CompactPaneLength = 68;
        _nav.OpenPaneLength = 68;
        _nav.AlwaysShowHeader = false;
        _nav.FontFamily = new FontFamily("Segoe UI Variable, Segoe UI");
        AddNav("chats", "\uE8F2", "NavChats");
        AddNav("nearby", "\uE701", "NavNearby");
        AddNav("settings", "\uE713", "NavSettings", true);
        _nav.SelectionChanged += (_, e) =>
        {
            if (e.SelectedItem is NavigationViewItem item && item.Tag is string tab && tab != _tab)
                Run(async () => { await SaveDraftAsync(); _tab = tab; RenderPage(); });
        };
        _nav.Content = _page;
        Grid.SetRow(_nav, 2);
        _root.Children.Add(_nav);
        App.StartupTrace("Navigation attached");
        _draftTimer.Tick += (_, _) => { _draftTimer.Stop(); Run(SaveDraftAsync); };
        _readTimer.Tick += (_, _) => ObserveVisibleBodies();
        _root.Loaded += async (_, _) =>
        {
            if (_loaded) return;
            _loaded = true;
            App.StartupTrace("Root loaded; rendering page");
            RenderPage();
            App.StartupTrace("Initial page rendered");
            try
            {
                string? profile = null;
                var args = Environment.GetCommandLineArgs();
                for (var i = 1; i + 1 < args.Length; i++) if (args[i] == "--profile") profile = args[++i];
                await _bridge.StartAsync(profile);
                if (_bridge.Current is {} initial) ApplyState(initial);
                _readTimer.Start();
            }
            catch (Exception e) { ReportError(e.Message); }
        };
        _bridge.StateChanged += state => DispatcherQueue.TryEnqueue(() => ApplyState(state));
        _bridge.Notice += message => DispatcherQueue.TryEnqueue(() => ShowNotice(message));
        _bridge.Failed += message => DispatcherQueue.TryEnqueue(() => ReportError(message));
        Activated += (_, e) => { _active = e.WindowActivationState != WindowActivationState.Deactivated; if (!_active) ResetDwell(); };
        Closed += async (_, _) =>
        {
            _closed = true;
            _readTimer.Stop();
            _draftTimer.Stop();
            try { await SaveDraftAsync(); } catch { }
            finally { try { await _bridge.DisposeAsync(); } catch { } }
        };
        _root.ActualThemeChanged += (_, _) =>
        {
            if (!_loaded) return;
            _root.Background = CanvasBrush();
            UpdateCaptionColors();
            _appearance = "";
            RenderPage();
        };
    }

    private void AddNav(string tag, string glyph, string id, bool footer = false)
    {
        var item = new NavigationViewItem { Tag = tag, Content = T(tag), Icon = new FontIcon { Glyph = glyph } };
        AutomationProperties.SetAutomationId(item, id);
        ToolTipService.SetToolTip(item, T(tag));
        if (footer) _nav.FooterMenuItems.Add(item); else _nav.MenuItems.Add(item);
    }

    private void ApplyState(ClientState state)
    {
        if (_closed) return;
        _state = state;
        var appearance = $"{state.Theme}|{state.EffectiveLanguage}|{state.Language}|{state.FontSize}";
        var changed = _appearance != appearance;
        _appearance = appearance;
        _root.RequestedTheme = state.Theme == "dark" ? ElementTheme.Dark : state.Theme == "light" ? ElementTheme.Light : ElementTheme.Default;
        _root.Background = CanvasBrush();
        UpdateCaptionColors();
        _root.FlowDirection = state.Rtl ? FlowDirection.RightToLeft : FlowDirection.LeftToRight;
        _titleStatus.Text = string.IsNullOrWhiteSpace(state.ConnectedPeerId) ? T("privateLocal") : T("encrypted") + " · " + TransportName(state.Transport);
        foreach (var item in _nav.MenuItems.Concat(_nav.FooterMenuItems).OfType<NavigationViewItem>())
        {
            item.Content = T((string)item.Tag);
            ToolTipService.SetToolTip(item, item.Content);
        }
        if (changed) RenderPage();
        else if (_tab == "chats") { RenderPeers(); RenderChat(); }
        else if (_tab == "nearby") RenderNearby();
        if (state.Approval is { } approval && approval.Token != _approvalShown)
        {
            _approvalShown = approval.Token;
            Run(() => ShowApprovalAsync(approval));
        }
    }

    private void RenderPage()
    {
        if (_composer is not null) { _cachedDraftPeer = _composerPeer; _cachedDraftText = _composer.Text; _cachedDraftDirty = _draftDirty; _cachedDraftRevision = _draftRevision; }
        _incomingBodies.Clear();
        ResetDwell();
        _page.Children.Clear();
        _page.ColumnDefinitions.Clear();
        _page.RowDefinitions.Clear();
        _renderedMessages = "";
        _peerSignature = "";
        _composer = null;
        _send = null;
        _peers = null;
        _messages = null;
        _messageStack = null;
        _chatPanel = null;
        if (_tab == "settings") RenderSettings();
        else if (_tab == "nearby") RenderNearby();
        else BuildChats();
    }

    private async void Run(Func<Task> action)
    {
        try { await action(); }
        catch (OperationCanceledException) { }
        catch (Exception e) { ReportError(e.Message); }
    }
    private async Task Call(string operation, object? args = null) => await _bridge.CallAsync(operation, args);
    public void ReportError(string message) => ShowNotice(message, InfoBarSeverity.Error);
    private void ShowNotice(string message, InfoBarSeverity severity = InfoBarSeverity.Informational)
    {
        _notice.Title = severity == InfoBarSeverity.Error ? T("actionFailed") : "";
        _notice.Message = message;
        _notice.Severity = severity;
        _notice.IsOpen = true;
    }
    private async Task<ContentDialogResult> DialogAsync(string title, UIElement body, string? primary = null, string? secondary = null)
    {
        await _dialogGate.WaitAsync();
        _modalDepth++; ResetDwell();
        try
        {
            var scroll = new ScrollViewer { Content = body, VerticalScrollBarVisibility = ScrollBarVisibility.Auto, HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled, MaxHeight = Math.Max(180, _root.ActualHeight - 240) };
            var dialog = new ContentDialog
            {
                XamlRoot = _root.XamlRoot,
                RequestedTheme = _root.RequestedTheme,
                Title = title,
                Content = scroll,
                PrimaryButtonText = primary ?? "",
                SecondaryButtonText = secondary ?? "",
                CloseButtonText = T("close"),
                DefaultButton = ContentDialogButton.Close,
                MaxWidth = 760
            };
            return await dialog.ShowAsync();
        }
        finally { _modalDepth--; ResetDwell(); _dialogGate.Release(); }
    }

    private TextBlock Text(string value, double size = 14, bool muted = false) => new()
    {
        Text = value, FontSize = size * new[] { 1d, 1.17, 1.32 }[Math.Clamp(_state.FontSize, 0, 2)],
        TextWrapping = TextWrapping.Wrap, Opacity = muted ? .62 : 1,
        FontFamily = new FontFamily("Segoe UI Variable, Segoe UI")
    };
    private TextBlock Heading(string value, double size = 28)
    {
        var text = Text(value, size); text.FontWeight = Microsoft.UI.Text.FontWeights.SemiBold; return text;
    }
    private Button Button(string text, Action action, string? glyph = null, string? id = null)
    {
        var button = new Button { Padding = new Thickness(12, 8, 12, 8) };
        if (glyph is null) button.Content = text;
        else
        {
            var panel = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
            panel.Children.Add(new FontIcon { Glyph = glyph, FontSize = 15 });
            if (text.Length > 0) panel.Children.Add(new TextBlock { Text = text });
            button.Content = panel;
        }
        button.Click += (_, _) => action();
        AutomationProperties.SetName(button, text);
        if (id is not null) AutomationProperties.SetAutomationId(button, id);
        ToolTipService.SetToolTip(button, text);
        return button;
    }
    private Button Command(string text, string op, object? args = null, string? glyph = null) => Button(text, () => Run(() => Call(op, args)), glyph);
    private Border Card(UIElement content, Thickness? padding = null) => new()
    {
        Child = content, Background = CardBrush(), BorderBrush = LineBrush(), BorderThickness = new Thickness(1),
        CornerRadius = new CornerRadius(10), Padding = padding ?? new Thickness(20)
    };
    private StackPanel Stack(double spacing = 12) => new() { Spacing = spacing };
    private StackPanel Row(double spacing = 8) => new() { Orientation = Orientation.Horizontal, Spacing = spacing };
    private Brush CanvasBrush() => new SolidColorBrush(IsDark ? ColorHelper.FromArgb(255, 25, 27, 31) : ColorHelper.FromArgb(255, 246, 247, 249));
    private Brush CardBrush() => new SolidColorBrush(IsDark ? ColorHelper.FromArgb(255, 33, 36, 41) : Colors.White);
    private Brush LineBrush() => new SolidColorBrush(IsDark ? ColorHelper.FromArgb(255, 54, 57, 62) : ColorHelper.FromArgb(255, 231, 233, 237));
    private Brush AccentBrush() => new SolidColorBrush(IsDark ? ColorHelper.FromArgb(255, 111, 193, 230) : ColorHelper.FromArgb(255, 0, 103, 151));
    private void UpdateCaptionColors()
    {
        if (!Microsoft.UI.Windowing.AppWindowTitleBar.IsCustomizationSupported()) return;
        var bar = AppWindow.TitleBar;
        bar.ButtonBackgroundColor = Colors.Transparent;
        bar.ButtonInactiveBackgroundColor = Colors.Transparent;
        bar.ButtonForegroundColor = IsDark ? Colors.White : Colors.Black;
        bar.ButtonInactiveForegroundColor = IsDark ? ColorHelper.FromArgb(255, 160, 164, 170) : ColorHelper.FromArgb(255, 110, 114, 120);
    }
    private bool IsDark => _root.ActualTheme == ElementTheme.Dark;
    private string? SelectedPeer => _state.SelectedPeerId;
    private Peer? SelectedPeerInfo => _state.History.FirstOrDefault(p => p.Id == SelectedPeer);
    private bool CurrentSession(string peer, string? generation) => peer == _state.SelectedPeerId && !string.IsNullOrEmpty(generation) && generation == _state.Generation;
    private void RequireSession(string peer, string? generation)
    {
        if (!CurrentSession(peer, generation)) throw new InvalidOperationException(T("sessionChanged"));
    }
    private static string Size(long bytes) => bytes >= 1024L * 1024 * 1024 ? $"{bytes / (1024d * 1024 * 1024):0.#} GB" : bytes >= 1024 * 1024 ? $"{bytes / (1024d * 1024):0.#} MB" : bytes >= 1024 ? $"{bytes / 1024d:0.#} KB" : $"{bytes} B";
    private static string Time(long millis)
    {
        if (millis <= 0) return "";
        try { var date = DateTimeOffset.FromUnixTimeMilliseconds(millis).LocalDateTime; return date.Date == DateTime.Today ? date.ToString("HH:mm") : date.ToString("MM-dd HH:mm"); } catch { return ""; }
    }
    private string TransportName(string? value) => value?.Contains("bluetooth", StringComparison.OrdinalIgnoreCase) == true ? T("bluetooth") : value?.Contains("lan", StringComparison.OrdinalIgnoreCase) == true ? T("lan") : value ?? "";
}
