using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Windows.System;
using WoZai.Next.Mesh;

namespace WoZai.Next;

public sealed partial class MainWindow
{
    private MeshClient? _mesh;
    private StackPanel? _meshMessages;
    private ScrollViewer? _meshScroll;
    private TextBlock? _meshStatus;
    private TextBox? _meshInput;
    private Button? _meshSend, _meshToggle;
    private ToggleSwitch? _meshRelay;
    private string _meshDraft = "";
    private bool _meshSending;
    private void InitializeMesh()
    {
        _mesh = new MeshClient(action => { if (!DispatcherQueue.TryEnqueue(() => action())) action(); }, _startupProfile);
        _mesh.Changed += () => { if (!_closed) UpdateMesh(); };
    }
    private void RenderMesh()
    {
        var pane = new Grid { Margin = new Thickness(30,20,30,30), MaxWidth = 930 };
        pane.RowDefinitions.Add(new() { Height = GridLength.Auto });
        pane.RowDefinitions.Add(new() { Height = new GridLength(1,GridUnitType.Star) });
        pane.RowDefinitions.Add(new() { Height = GridLength.Auto });
        var body = Stack(16); pane.Children.Add(body);
        body.Children.Add(Heading(T("mesh")));
        body.Children.Add(Text(T("meshPublic"), 13, true));
        _meshStatus = Text("", 14); body.Children.Add(_meshStatus);
        var controls = Row();
        _meshToggle = Button(T("meshStart"), () => Run(async () => {
            if (_mesh is null) return;
            if (_mesh.Running || _mesh.Starting) await _mesh.StopAsync(); else await _mesh.StartAsync();
        }), id: "MeshStartStop");
        controls.Children.Add(_meshToggle);
        controls.Children.Add(Button(T("bluetoothSettings"), () => Run(async () => { await Launcher.LaunchUriAsync(new Uri("ms-settings:bluetooth")); })));
        _meshRelay = new ToggleSwitch { Header = T("meshRelay"), OnContent = T("meshRelayOn"), OffContent = T("meshRelayOff") };
        AutomationProperties.SetAutomationId(_meshRelay, "MeshRelay");
        _meshRelay.Toggled += (_, _) => { if (_mesh?.Running == true && _meshRelay.IsOn != _mesh.Relay) Run(() => _mesh.RelayAsync(_meshRelay.IsOn)); };
        controls.Children.Add(_meshRelay); body.Children.Add(controls);
        body.Children.Add(Text(T("meshRelayHint"), 12, true));
        _meshMessages = Stack(12);
        _meshScroll = new ScrollViewer { Content = _meshMessages, Margin = new Thickness(0,16,0,16), VerticalScrollBarVisibility = ScrollBarVisibility.Auto };
        Grid.SetRow(_meshScroll,1); pane.Children.Add(_meshScroll);
        var footer = Stack(8); Grid.SetRow(footer,2); pane.Children.Add(footer);
        _meshInput = new TextBox { Text = _meshDraft, PlaceholderText = T("meshInput"), AcceptsReturn = true, MaxLength = 4096, MinHeight = 72, MaxHeight = 160, TextWrapping = TextWrapping.Wrap };
        AutomationProperties.SetAutomationId(_meshInput, "MeshInput");
        _meshInput.TextChanged += (_, _) => { _meshDraft = _meshInput.Text; UpdateMeshControls(); };
        footer.Children.Add(_meshInput);
        _meshSend = Button(T("send"), () => Run(SendMeshAsync), id: "MeshSend");
        footer.Children.Add(_meshSend);
        AutomationProperties.SetAutomationId(pane, "MeshPage"); _page.Children.Add(pane);
        UpdateMesh();
    }
    private async Task SendMeshAsync()
    {
        if (_mesh is null || _meshSending) return;
        var text = _meshDraft; _meshSending = true; UpdateMeshControls();
        try {
            await _mesh.SendAsync(text);
            if (_meshDraft == text) { _meshDraft = ""; if (_meshInput is not null) _meshInput.Text = ""; }
        } catch { ShowNotice(T("meshSendFailed")); } finally { _meshSending = false; UpdateMeshControls(); }
    }
    private void UpdateMeshControls()
    {
        if (_tab != "mesh" || _mesh is null) return;
        if (_meshSend is not null) _meshSend.IsEnabled = _mesh.Running && !_meshSending && !string.IsNullOrWhiteSpace(_meshDraft) && System.Text.Encoding.UTF8.GetByteCount(_meshDraft) <= 2048;
    }
    private void UpdateMesh()
    {
        if (_tab != "mesh" || _mesh is null || _meshStatus is null || _meshMessages is null) return;
        _meshStatus.Text = (_mesh.Error.Length > 0 ? T(_mesh.Error) : T(_mesh.Starting ? "meshStarting" : _mesh.Running ? "meshLooking" : "meshOff")) + " · " + T("meshNeighbors") + ": " + _mesh.Neighbors;
        if (_meshToggle is not null) _meshToggle.Content = T(_mesh.Running || _mesh.Starting ? "meshStop" : "meshStart");
        if (_meshRelay is not null) { _meshRelay.IsEnabled = _mesh.Running; _meshRelay.IsOn = _mesh.Relay; }
        bool follow = _meshScroll is null || _meshScroll.ScrollableHeight - _meshScroll.VerticalOffset < 32;
        _meshMessages.Children.Clear();
        foreach (var message in _mesh.Messages) {
            var content = Stack(5); content.Children.Add(Text(message.Own ? T("meshMe") : T("meshPeer"),12,true));
            var text = Text(message.Body,15); text.IsTextSelectionEnabledCompat(); content.Children.Add(text);
            content.Children.Add(Text(T(message.State),12,true)); _meshMessages.Children.Add(Card(content));
        }
        if (_mesh.Messages.Count == 0) _meshMessages.Children.Add(Text(T("meshEmpty"),14,true));
        if (follow && _meshScroll is not null) DispatcherQueue.TryEnqueue(() => { _meshScroll.UpdateLayout(); _meshScroll.ChangeView(null,_meshScroll.ScrollableHeight,null); });
        UpdateMeshControls();
    }
}
