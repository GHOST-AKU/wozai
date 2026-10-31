using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Windows.ApplicationModel.DataTransfer;
using Windows.Storage;
using Windows.System;

namespace WoZai.Next;

public sealed partial class MainWindow
{
    private TextBox? _connectAddress;
    private bool _nearbyRefreshPending;
    private void RenderNearby()
    {
        if (_tab != "nearby") return;
        // Discovery snapshots must not destroy the address editor while the user types.
        if (_connectAddress?.FocusState is FocusState.Keyboard or FocusState.Pointer or FocusState.Programmatic)
        { _nearbyRefreshPending = true; return; }
        _nearbyRefreshPending = false;
        _page.Children.Clear();
        var body = Stack(22); body.MaxWidth = 930; body.HorizontalAlignment = HorizontalAlignment.Stretch; body.Margin = new Thickness(30, 20, 30, 30);
        body.Children.Add(Heading(T("nearby")));
        body.Children.Add(Text(T("nearbySummary"), 14, true));
        var connection = Stack(14);
        var top = new Grid(); top.ColumnDefinitions.Add(new() { Width = new GridLength(1, GridUnitType.Star) }); top.ColumnDefinitions.Add(new() { Width = GridLength.Auto });
        var status = Stack(5); status.Children.Add(Heading(T("connectionStatus"), 17)); status.Children.Add(Text(StatusName(_state.Phase) + (string.IsNullOrEmpty(_state.ConnectedPeerId) ? "" : " · " + (SelectedPeerInfo?.Name ?? T("connected"))), 13, true)); top.Children.Add(status);
        var refresh = Command(T("refresh"), "refresh", glyph: "\uE72C"); refresh.VerticalAlignment = VerticalAlignment.Center; Grid.SetColumn(refresh, 1); top.Children.Add(refresh); connection.Children.Add(top);
        if (_state.ConnectedPeerId is not null) connection.Children.Add(Command(T("disconnect"), "disconnect"));
        body.Children.Add(Card(connection));

        var radio = Stack(18);
        radio.Children.Add(Heading(T("receiveDiscover"), 17));
        void AddTransport(string mode, bool listening, bool searching, string label, string explanation)
        {
            var row = new Grid { ColumnSpacing = 14 };
            row.ColumnDefinitions.Add(new() { Width = new GridLength(1, GridUnitType.Star) }); row.ColumnDefinitions.Add(new() { Width = GridLength.Auto });
            var labels = Stack(4); labels.Children.Add(Text(label, 15)); labels.Children.Add(Text(explanation, 12, true)); row.Children.Add(labels);
            var toggle = new ToggleSwitch { IsOn = listening, OnContent = T("receiving"), OffContent = T("off"), VerticalAlignment = VerticalAlignment.Center };
            AutomationProperties.SetName(toggle, label + " " + T("receive")); AutomationProperties.SetAutomationId(toggle, mode == "lan" ? "LanListening" : "BluetoothListening");
            toggle.Toggled += (_, _) => Run(() => Call("listen", new { mode, enabled = toggle.IsOn }));
            Grid.SetColumn(toggle, 1); row.Children.Add(toggle); radio.Children.Add(row);
            var actions = Row();
            actions.Children.Add(Command(T(searching ? "stopSearch" : "searchDevices"), searching ? "stopSearch" : "search", new { mode }, searching ? "\uE71A" : "\uE721"));
            if (mode == "bluetooth") actions.Children.Add(Button(T("bluetoothSettings"), () => Run(async () => { await Launcher.LaunchUriAsync(new Uri("ms-settings:bluetooth")); }), "\uE713"));
            radio.Children.Add(actions);
        }
        AddTransport("lan", _state.LanListening, _state.SearchingLan, T("lan"), T("lanBody"));
        AddTransport("bluetooth", _state.BluetoothListening, _state.SearchingBluetooth, T("bluetooth"), T("bluetoothBody"));
        body.Children.Add(Card(radio));
        var direct = Stack(12); direct.Children.Add(Heading(T("directConnect"), 17));
        var address = new TextBox { PlaceholderText = "192.168.1.20:42673 / bt://…", Text = _addressText };
        _connectAddress = address;
        AutomationProperties.SetAutomationId(address, "ConnectAddress"); address.TextChanged += (_, _) => _addressText = address.Text;
        address.LostFocus += (_, _) => { if (_nearbyRefreshPending) DispatcherQueue.TryEnqueue(() => RenderNearby()); };
        direct.Children.Add(address);
        direct.Children.Add(Button(T("connect"), () => Run(async () => { if (string.IsNullOrWhiteSpace(address.Text)) return; await ConnectAsync(address.Text.Trim()); }), "\uE774", "ConnectButton"));
        if (_state.Endpoints.Length > 0)
        {
            direct.Children.Add(Text(T("myAddresses"), 12, true));
            foreach (var endpoint in _state.Endpoints)
            {
                var endpointRow = Row(); var value = Text(endpoint, 13); value.IsTextSelectionEnabledCompat(); endpointRow.Children.Add(value);
                endpointRow.Children.Add(Button(T("copy"), () => Copy(endpoint), "\uE8C8")); direct.Children.Add(endpointRow);
            }
        }
        body.Children.Add(Card(direct));
        var devices = Stack(14); devices.Children.Add(Heading(T("discoveredDevices"), 17));
        if (_state.Nearby.Length == 0) devices.Children.Add(Text(T("noNearby"), 13, true));
        foreach (var peer in _state.Nearby)
        {
            var row = new Grid { ColumnSpacing = 12 };
            row.ColumnDefinitions.Add(new() { Width = GridLength.Auto }); row.ColumnDefinitions.Add(new() { Width = new GridLength(1, GridUnitType.Star) }); row.ColumnDefinitions.Add(new() { Width = GridLength.Auto });
            row.Children.Add(InitialAvatar(peer.Name, 40));
            var detail = Stack(4); detail.Children.Add(Text(peer.Name, 15)); detail.Children.Add(Text(TransportName(peer.Transport) + " · " + peer.Endpoint, 12, true)); Grid.SetColumn(detail, 1); row.Children.Add(detail);
            var connect = Button(T("connect"), () => Run(() => ConnectAsync(peer.Endpoint,peer.Trusted?peer.Id:null)));
            Grid.SetColumn(connect, 2); row.Children.Add(connect); devices.Children.Add(row);
        }
        body.Children.Add(Card(devices));
        body.Children.Add(Text(T("discoveryNotTrust"), 12, true));
        var scroll = new ScrollViewer { Content = body, VerticalScrollBarVisibility = ScrollBarVisibility.Auto, HorizontalContentAlignment = HorizontalAlignment.Stretch };
        AutomationProperties.SetAutomationId(scroll, "NearbyPage"); _page.Children.Add(scroll);
    }

    private void RenderSettings()
    {
        var body = Stack(22); body.MaxWidth = 930; body.Margin = new Thickness(30, 20, 30, 30); body.HorizontalAlignment = HorizontalAlignment.Stretch;
        body.Children.Add(Heading(T("settings")));
        body.Children.Add(Text(T("settingsSummary"), 14, true));
        var identity = Stack(14); identity.Children.Add(Heading(T("myProfile"), 17));
        var account = Row(16); account.Children.Add(ProfileAvatar(58));
        var accountLabels = Stack(5); accountLabels.VerticalAlignment = VerticalAlignment.Center; accountLabels.Children.Add(Text(_state.Nickname, 17)); accountLabels.Children.Add(Text(T("independentIdentity"), 12, true)); account.Children.Add(accountLabels); identity.Children.Add(account);
        identity.Children.Add(Button(T("editAvatar"), () => Run(EditAvatarAsync), "\uE77B", "EditAvatarButton"));
        var nickname = new TextBox { Text = _state.Nickname, MaxLength = 64, MinWidth = 210 };
        AutomationProperties.SetAutomationId(nickname, "Nickname");
        var nameRow = Row(); nameRow.Children.Add(nickname); nameRow.Children.Add(Button(T("save"), () => Run(() => Call("settings", new { nickname = nickname.Text }))));
        identity.Children.Add(SettingRow(T("nickname"), nameRow));
        var id = Text(_state.LocalId, 11, true); id.IsTextSelectionEnabledCompat(); identity.Children.Add(id);
        body.Children.Add(Card(identity));

        var appearance = Stack(16); appearance.Children.Add(Heading(T("appearance"), 17));
        var language = new ComboBox { MinWidth = 210 };
        var tags = new List<string> { "system" };
        language.Items.Add(T("systemLanguage"));
        var languages = _state.Languages.Length > 0 ? _state.Languages : new[] { new LanguageOption { Tag = "zh-Hans", NativeName = "简体中文" }, new LanguageOption { Tag = "en", NativeName = "English" }, new LanguageOption { Tag = "zh-Hant", NativeName = "繁體中文" }, new LanguageOption { Tag = "ja", NativeName = "日本語" }, new LanguageOption { Tag = "ko", NativeName = "한국어" } };
        foreach (var locale in languages) { tags.Add(locale.Tag); language.Items.Add(locale.NativeName); }
        language.SelectedIndex = Math.Max(0, tags.IndexOf(_state.Language));
        language.SelectionChanged += (_, _) => { if (language.SelectedIndex >= 0) Run(() => Call("settings", new { language = tags[language.SelectedIndex] })); };
        AutomationProperties.SetAutomationId(language, "LanguageSetting"); appearance.Children.Add(SettingRow(T("language"), language));
        var themeOptions=Row(12);
        foreach(var value in new[]{"system","light","dark"}) {
            var option=new RadioButton{Content=T(value=="system"?"systemTheme":value),GroupName="Theme",IsChecked=_state.Theme==value};
            AutomationProperties.SetAutomationId(option,"Theme"+char.ToUpperInvariant(value[0])+value.Substring(1));
            option.Checked+=(_,_)=>Run(()=>Call("settings",new {theme=value}));themeOptions.Children.Add(option);
        }
        appearance.Children.Add(SettingRow(T("theme"),themeOptions));
        var font = new ComboBox { MinWidth = 210 }; foreach (var key in new[] { "normal", "large", "largest" }) font.Items.Add(T(key)); font.SelectedIndex = Math.Clamp(_state.FontSize, 0, 2);
        font.SelectionChanged += (_, _) => { if (font.SelectedIndex >= 0) Run(() => Call("settings", new { fontSize = font.SelectedIndex })); };
        AutomationProperties.SetAutomationId(font, "FontSetting"); appearance.Children.Add(SettingRow(T("fontSize"), font)); body.Children.Add(Card(appearance));

        var privacy = Stack(15); privacy.Children.Add(Heading(T("privacyStorage"), 17));
        var receipts = new ToggleSwitch { IsOn = _state.ReadReceipts, OnContent = T("on"), OffContent = T("off") };
        receipts.Toggled += (_, _) => Run(() => Call("settings", new { readReceipts = receipts.IsOn }));
        AutomationProperties.SetAutomationId(receipts, "ReadReceiptsSetting"); privacy.Children.Add(SettingRow(T("readReceipts"), receipts)); privacy.Children.Add(Text(T("readReceiptsBody"), 12, true));
        var quota = new ComboBox { MinWidth = 210 }; var quotas = new[] { 16L, 32, 64, 128 }; foreach (var q in quotas) quota.Items.Add($"{q} GB"); quota.SelectedIndex = Array.FindIndex(quotas, q => q * 1024 * 1024 * 1024 == _state.Quota);
        quota.SelectionChanged += (_, _) => { if (quota.SelectedIndex >= 0) Run(() => Call("settings", new { quota = quotas[quota.SelectedIndex] * 1024 * 1024 * 1024 })); };
        privacy.Children.Add(SettingRow(T("quota"), quota));
        privacy.Children.Add(Button(T("trustedDevices"), () => Run(ShowTrustAsync), "\uE72E"));
        privacy.Children.Add(Button(T("stopAll"), () => Run(async () => { await Call("stopSearch", new { mode = "lan" }); await Call("stopSearch", new { mode = "bluetooth" }); await Call("listen", new { mode = "lan", enabled = false }); await Call("listen", new { mode = "bluetooth", enabled = false }); await Call("disconnect"); })));
        body.Children.Add(Card(privacy));
        var data = Stack(12); data.Children.Add(Heading(T("dataFolder"), 17));
        var path = Text(_state.DataPath, 12, true); path.IsTextSelectionEnabledCompat(); data.Children.Add(path); data.Children.Add(Text(T("dataBody"), 12, true));
        data.Children.Add(Button(T("openFolder"), () => Run(async () => { var folder = await StorageFolder.GetFolderFromPathAsync(_state.DataPath); await Launcher.LaunchFolderAsync(folder); }), "\uE8B7"));
        body.Children.Add(Card(data));
        var about = Stack(12); about.Children.Add(Heading(T("about"), 17)); about.Children.Add(Text("我在next · " + _state.Version, 14)); about.Children.Add(Text(T("aboutBody"), 12, true));about.Children.Add(Text(T("privacy"),12,true));
        var links = Row(); links.Children.Add(Button(T("help"), () => Run(async () => { await DialogAsync(T("help"), Text(T("helpBody"))); }))); links.Children.Add(Button(T("diagnostics"), () => Run(ShowDiagnosticsAsync))); about.Children.Add(links); body.Children.Add(Card(about));
        var scroll = new ScrollViewer { Content = body, VerticalScrollBarVisibility = ScrollBarVisibility.Auto, HorizontalContentAlignment = HorizontalAlignment.Stretch };
        AutomationProperties.SetAutomationId(scroll, "SettingsPage"); _page.Children.Add(scroll);
    }

    private Grid SettingRow(string label, FrameworkElement control)
    {
        var row = new Grid { ColumnSpacing = 18 };
        row.ColumnDefinitions.Add(new() { Width = new GridLength(1, GridUnitType.Star) }); row.ColumnDefinitions.Add(new() { Width = GridLength.Auto });
        var name = Text(label, 14); name.VerticalAlignment = VerticalAlignment.Center; row.Children.Add(name); Grid.SetColumn(control, 1); row.Children.Add(control); AutomationProperties.SetName(control, label); return row;
    }
    private async Task ConnectAsync(string endpoint,string? expectedId=null)
    {
        if(_state.ConnectedPeerId is not null || _state.Phase=="connecting") {
            if(await DialogAsync(T("connectionSwitchTitle"),Text(T("connectionSwitchBody")),T("disconnectAndConnect"))!=ContentDialogResult.Primary)return;
            await Call("disconnect");
        }
        await Call("connect",new{endpoint,expectedId});
    }
    private async Task ShowApprovalAsync(Approval approval)
    {
        var panel = Stack(14); panel.MinWidth = 320;
        panel.Children.Add(Heading(approval.Name, 21)); panel.Children.Add(Text(T("approvalBody"), 13));
        panel.Children.Add(Text(T("identity") + "\n" + approval.Id, 12, true));
        var fingerprint = Text(approval.Fingerprint, 14); fingerprint.IsTextSelectionEnabledCompat(); panel.Children.Add(fingerprint);
        var result = await DialogAsync(T("approveConnection"), panel, T("rememberApprove"), T("approveOnce"));
        if (_state.Approval?.Token != approval.Token) return;
        await Call(result == ContentDialogResult.None ? "reject" : "approve", new { token = approval.Token, remember = result == ContentDialogResult.Primary });
    }
    private async Task ShowTrustAsync()
    {
        var panel=Stack(14);panel.MinWidth=350;panel.Children.Add(Text(T("trustHint"),13,true));
        var list=new ListView{SelectionMode=ListViewSelectionMode.Single,MaxHeight=300};
        foreach(var peer in _state.History.Where(p=>p.Trusted&&p.Id is not null))list.Items.Add(new ListViewItem{Tag=peer,Content=peer.Name});
        panel.Children.Add(list);
        if(await DialogAsync(T("trustedDevices"),panel,T("revoke"))==ContentDialogResult.Primary && list.SelectedItem is ListViewItem item && item.Tag is Peer peerToRevoke)await ConfirmRevokeAsync(peerToRevoke);
    }
    private async Task ConfirmRevokeAsync(Peer peer)
    {
        if(await DialogAsync(T("revoke")+" · "+peer.Name,Text(T("trustHint")),T("revoke"))==ContentDialogResult.Primary)await Call("revoke",new{peerId=peer.Id});
    }
    private async Task ShowPeerDetailsAsync()
    {
        var peer = SelectedPeerInfo; if (peer?.Id is null) return;
        var panel = Stack(15); panel.MinWidth = 360;
        var avatar = await _bridge.CallAsync<Avatar>("avatar", new { peerId = peer.Id });
        if (!string.IsNullOrEmpty(avatar?.Png)) panel.Children.Add(await ImageFromPngAsync(avatar.Png, 96)); else panel.Children.Add(InitialAvatar(peer.Name, 72));
        panel.Children.Add(Heading(peer.Name, 22)); panel.Children.Add(Text(peer.Trusted ? T("trustedDevice") : T("notRemembered"), 13, true));
        var details = Text(T("identity") + "\n" + peer.Id + "\n\n" + T("address") + "\n" + peer.Endpoint + "\n\n" + T("publicKey") + "\n" + peer.PublicKey, 12); details.IsTextSelectionEnabledCompat(); panel.Children.Add(details);
        var canConnect=!string.IsNullOrEmpty(peer.Endpoint)&&_state.ConnectedPeerId!=peer.Id;
        var result=await DialogAsync(T("deviceDetails"),panel,canConnect?T("reconnect"):null,peer.Trusted?T("revoke"):null);
        if(result==ContentDialogResult.Primary&&canConnect)await ConnectAsync(peer.Endpoint,peer.Id);
        else if(result==ContentDialogResult.Secondary&&peer.Trusted)await ConfirmRevokeAsync(peer);
    }
    private async Task ShowDiagnosticsAsync()
    {
        var body = Text(_state.Diagnostics, 12); body.IsTextSelectionEnabledCompat();
        await DialogAsync(T("diagnostics"), body);
    }
    private void Copy(string text) { var package = new DataPackage(); package.SetText(text); Clipboard.SetContent(package); ShowNotice(T("copied")); }
}

internal static class TextSelectionExtensions
{
    internal static void IsTextSelectionEnabledCompat(this TextBlock text) => text.IsTextSelectionEnabled = true;
}
