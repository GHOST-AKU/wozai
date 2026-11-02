using Microsoft.UI;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Windows.System;
using WoZai.Next.Services;

namespace WoZai.Next;

public sealed partial class MainWindow
{
    private readonly Dictionary<string,Message> _historyMessages = new();
    private string? _historyPeer, _historyBefore;
    private bool _historyPaged, _historyHasMore;
    private string _historyQuery = "";
    private long _historyEpoch;
    private bool _refreshingHistory, _historyRefreshAgain;
    private void BuildChats()
    {
        _page.ColumnDefinitions.Add(new() { Width = new GridLength(282) });
        _page.ColumnDefinitions.Add(new() { Width = new GridLength(1, GridUnitType.Star) });
        var sidebar = new Grid { Padding = new Thickness(18, 16, 18, 16), Background = CardBrush() };
        sidebar.RowDefinitions.Add(new() { Height = GridLength.Auto });
        sidebar.RowDefinitions.Add(new() { Height = GridLength.Auto });
        sidebar.RowDefinitions.Add(new() { Height = new GridLength(1, GridUnitType.Star) });
        var heading = new Grid { Margin = new Thickness(2, 0, 0, 18) };
        heading.Children.Add(Heading(T("chats"), 25));
        var add = Button(T("newConnection"), () => Navigate("nearby"), "\uE710");
        add.Content = new FontIcon { Glyph = "\uE710", FontSize = 16 };
        add.HorizontalAlignment = HorizontalAlignment.Right;
        heading.Children.Add(add);
        sidebar.Children.Add(heading);
        var search = new TextBox { PlaceholderText = T("searchChats"), Text = _peerQuery, Margin = new Thickness(0, 0, 0, 12) };
        AutomationProperties.SetAutomationId(search, "ConversationSearch");
        search.TextChanged += (_, _) => { _peerQuery = search.Text; RenderPeers(); };
        Grid.SetRow(search, 1);
        sidebar.Children.Add(search);
        _peers = new ListView { SelectionMode = ListViewSelectionMode.Single, HorizontalContentAlignment = HorizontalAlignment.Stretch, Background = null, BorderThickness = new Thickness(0), Padding = new Thickness(0) };
        AutomationProperties.SetAutomationId(_peers, "ConversationList");
        _peers.SelectionChanged += (_, _) =>
        {
            if (_renderingPeers || _peers.SelectedItem is not ListViewItem item || item.Tag is not Peer peer || peer.Id is null || peer.Id == SelectedPeer) return;
            Run(async () => { await SaveDraftAsync(); await Call("select", new { peerId = peer.Id }); });
        };
        Grid.SetRow(_peers, 2);
        sidebar.Children.Add(_peers);
        _page.Children.Add(sidebar);
        _chatPanel = new Grid { Margin = new Thickness(1, 0, 0, 0), Background = CanvasBrush() };
        Grid.SetColumn(_chatPanel, 1);
        _page.Children.Add(_chatPanel);
        RenderPeers();
        RenderChat();
    }

    private void Navigate(string tab)
    {
        var item = _nav.MenuItems.Concat(_nav.FooterMenuItems).OfType<NavigationViewItem>().First(x => (string)x.Tag == tab);
        _nav.SelectedItem = item;
    }

    private void RenderPeers()
    {
        if (_peers is null) return;
        var signature="peers|"+SelectedPeer+"|"+_peerQuery+string.Join(";",_state.History.Select(p=>$"{p.Id}|{p.Name}|{p.Preview}|{p.Time}|{p.PublicKey}"));
        if(signature==_peerSignature)return;
        _peerSignature=signature;
        _renderingPeers = true;
        try
        {
            _peers.Items.Clear();
            foreach (var peer in _state.History.Where(p => string.IsNullOrWhiteSpace(_peerQuery) || p.Name.Contains(_peerQuery, StringComparison.CurrentCultureIgnoreCase)))
            {
                var item = new ListViewItem { Tag = peer, Padding = new Thickness(8, 13, 8, 13), HorizontalContentAlignment = HorizontalAlignment.Stretch };
                AutomationProperties.SetName(item, peer.Name);
                var grid = new Grid { ColumnSpacing = 11 };
                grid.ColumnDefinitions.Add(new() { Width = new GridLength(42) });
                grid.ColumnDefinitions.Add(new() { Width = new GridLength(1, GridUnitType.Star) });
                var avatar = DeviceAvatar(peer, 40);
                grid.Children.Add(avatar);
                var content = Stack(5);
                var first = new Grid();
                first.ColumnDefinitions.Add(new() { Width = new GridLength(1, GridUnitType.Star) });
                first.ColumnDefinitions.Add(new() { Width = GridLength.Auto });
                var name = Text(peer.Name, 14); name.TextTrimming = TextTrimming.CharacterEllipsis; name.TextWrapping = TextWrapping.NoWrap; name.FontWeight = Microsoft.UI.Text.FontWeights.SemiBold;
                first.Children.Add(name);
                var time = Text(Time(peer.Time), 11, true); time.Margin = new Thickness(8, 2, 0, 0); Grid.SetColumn(time, 1); first.Children.Add(time);
                content.Children.Add(first);
                var preview = Text(string.IsNullOrWhiteSpace(peer.Preview) ? peer.Trusted ? T("trustedDevice") : T("noMessages") : peer.Preview, 12, true);
                preview.TextTrimming = TextTrimming.CharacterEllipsis; preview.TextWrapping = TextWrapping.NoWrap;
                content.Children.Add(preview);
                Grid.SetColumn(content, 1); grid.Children.Add(content);
                item.Content = grid;
                _peers.Items.Add(item);
                if (peer.Id == SelectedPeer) _peers.SelectedItem = item;
            }
            if (_peers.Items.Count == 0)
                _peers.Items.Add(new ListViewItem { Content = Text(T(string.IsNullOrEmpty(_peerQuery) ? "noConversations" : "noResults"), 13, true), IsEnabled = false });
        }
        finally { _renderingPeers = false; }
    }

    private Border InitialAvatar(string name, double size)
    {
        var text = Text(string.IsNullOrWhiteSpace(name) ? "?" : System.Globalization.StringInfo.GetNextTextElement(name.Trim()), size * .38);
        text.HorizontalAlignment = HorizontalAlignment.Center; text.VerticalAlignment = VerticalAlignment.Center;
        text.Foreground = AccentBrush(); text.FontWeight = Microsoft.UI.Text.FontWeights.SemiBold;
        return new Border { Width = size, Height = size, CornerRadius = new CornerRadius(size / 2), Background = new SolidColorBrush(IsDark ? ColorHelper.FromArgb(255, 35, 64, 77) : ColorHelper.FromArgb(255, 229, 241, 248)), Child = text };
    }
    private Border DeviceAvatar(Peer peer,double size)
    {
        var border=InitialAvatar(peer.Name,size);
        if (!string.IsNullOrEmpty(peer.Id) && !string.IsNullOrEmpty(peer.PublicKey)) Run(async()=>{
            var avatar=await _bridge.CallAsync<Avatar>("avatar",new {peerId=peer.Id});
            if(!string.IsNullOrEmpty(avatar?.Png))border.Child=await ImageFromPngAsync(avatar.Png,size);
        });
        return border;
    }

    private void RenderChat()
    {
        if (_chatPanel is null) return;
        var peer = SelectedPeerInfo;
        if (peer is null || string.IsNullOrEmpty(SelectedPeer))
        {
            _chatPanel.Children.Clear(); _chatPanel.RowDefinitions.Clear(); _composer = null;
            var empty = Stack(16); empty.HorizontalAlignment = HorizontalAlignment.Center; empty.VerticalAlignment = VerticalAlignment.Center; empty.MaxWidth = 400; empty.Margin = new Thickness(36);
            var icon = new FontIcon { Glyph = "\uE8F2", FontSize = 52, Foreground = AccentBrush(), HorizontalAlignment = HorizontalAlignment.Center, Margin = new Thickness(0, 0, 0, 8) };
            empty.Children.Add(icon);
            var heading = Heading(T("welcome"), 27); heading.TextAlignment = TextAlignment.Center; empty.Children.Add(heading);
            var note = Text(T("welcomeBody"), 14, true); note.TextAlignment = TextAlignment.Center; empty.Children.Add(note);
            var connect = Button(T("findNearby"), () => Navigate("nearby"), "\uE701"); connect.HorizontalAlignment = HorizontalAlignment.Center; empty.Children.Add(connect);
            _chatPanel.Children.Add(empty);
            return;
        }
        if (_composer is null || _composerPeer != SelectedPeer)
        {
            _chatPanel.Children.Clear(); _chatPanel.RowDefinitions.Clear();
            _chatPanel.RowDefinitions.Add(new() { Height = GridLength.Auto });
            _chatPanel.RowDefinitions.Add(new() { Height = new GridLength(1, GridUnitType.Star) });
            _chatPanel.RowDefinitions.Add(new() { Height = GridLength.Auto });
            _composerPeer = SelectedPeer;
            _draftDirty = _cachedDraftPeer == SelectedPeer && _cachedDraftDirty;
            _renderedMessages = "";
            _readProgress.Clear();
            var header = new Grid { Padding = new Thickness(26, 17, 22, 17), Background = CardBrush(), ColumnSpacing = 12 };
            header.ColumnDefinitions.Add(new() { Width = GridLength.Auto });
            header.ColumnDefinitions.Add(new() { Width = new GridLength(1, GridUnitType.Star) });
            header.ColumnDefinitions.Add(new() { Width = GridLength.Auto });
            var avatar = Button(T("deviceDetails"), () => Run(ShowPeerDetailsAsync)); avatar.Content = DeviceAvatar(peer, 42); avatar.Padding = new Thickness(0); avatar.Background = null; avatar.BorderThickness = new Thickness(0); header.Children.Add(avatar);
            var info = Stack(3);
            _chatTitle = Heading(peer.Name, 19);
            _chatSubtitle = Text("", 12, true);
            info.Children.Add(_chatTitle); info.Children.Add(_chatSubtitle); Grid.SetColumn(info, 1); header.Children.Add(info);
            _chatActions = Row(8); _chatActions.VerticalAlignment = VerticalAlignment.Center;
            _reconnectButton=Button(T("reconnect"),()=>Run(async()=>{if(string.IsNullOrEmpty(peer.Endpoint)){Navigate("nearby");return;}await ConnectAsync(peer.Endpoint,peer.Id);}),"\uE72C");
            _chatActions.Children.Add(_reconnectButton);
            var verify = Button(T("security"), () => Run(ShowSecurityAsync), "\uE72E", "SecurityButton");
            _chatActions.Children.Add(verify);
            var more = new Button { Content = new FontIcon { Glyph = "\uE712", FontSize = 18 }, Padding = new Thickness(11) };
            AutomationProperties.SetName(more, T("more"));
            var flyout = new MenuFlyout();
            flyout.Opening+=(_,_)=>{_modalDepth++;ResetDwell();};
            flyout.Closed+=(_,_)=>{_modalDepth--;ResetDwell();};
            void Add(string label, Action action) { var i = new MenuFlyoutItem { Text = label }; i.Click += (_, _) => action(); flyout.Items.Add(i); }
            Add(T("searchMessages"), () => Run(SearchMessagesAsync));
            Add(T("deviceDetails"), () => Run(ShowPeerDetailsAsync));
            Add(T("batchQueue"), () => Run(ShowQueueAsync));
            Add(T("disconnect"), () => Run(() => Call("disconnect")));
            Add(T("clearHistory"), () => Run(ClearHistoryAsync));
            Add(T("diagnostics"), () => Run(ShowDiagnosticsAsync));
            more.Flyout = flyout; _chatActions.Children.Add(more);
            Grid.SetColumn(_chatActions, 2); header.Children.Add(_chatActions); _chatPanel.Children.Add(header);
            _messageStack = Stack(16); _messageStack.Padding = new Thickness(28, 22, 28, 22);
            _messages = new ScrollViewer { Content = _messageStack, VerticalScrollBarVisibility = ScrollBarVisibility.Auto, HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled };
            AutomationProperties.SetAutomationId(_messages, "MessageViewport");
            _messages.ViewChanged += (_, _) => ResetDwell();
            Grid.SetRow(_messages, 1); _chatPanel.Children.Add(_messages);
            var composerFrame = new Grid { Margin = new Thickness(24, 8, 24, 22), Padding = new Thickness(12), Background = CardBrush(), CornerRadius = new CornerRadius(12), BorderBrush = LineBrush(), BorderThickness = new Thickness(1), RowSpacing = 7 };
            composerFrame.RowDefinitions.Add(new() { Height = GridLength.Auto });
            composerFrame.RowDefinitions.Add(new() { Height = GridLength.Auto });
            _composer = new TextBox { AcceptsReturn = true, TextWrapping = TextWrapping.Wrap, MinHeight = 58, MaxHeight = 150, BorderThickness = new Thickness(0), Background = null, PlaceholderText = T("composerHint"), FontSize = 15 * new[] { 1d, 1.17, 1.32 }[Math.Clamp(_state.FontSize, 0, 2)] };
            AutomationProperties.SetAutomationId(_composer, "Composer");
            _applyingDraft = true; _composer.Text = _cachedDraftPeer == SelectedPeer && _cachedDraftDirty ? _cachedDraftText : _state.Draft; _applyingDraft = false;
            _composer.TextChanged += (_, _) =>
            {
                if (!_applyingDraft) { _draftDirty = true; _draftRevision++; _draftTimer.Stop(); _draftTimer.Start(); }
                UpdateComposer();
            };
            _composer.KeyDown += (_, e) =>
            {
                if (e.Key == VirtualKey.Enter && (Microsoft.UI.Input.InputKeyboardSource.GetKeyStateForCurrentThread(VirtualKey.Shift) & Windows.UI.Core.CoreVirtualKeyStates.Down) == 0)
                { e.Handled = true; if (_send?.IsEnabled == true) Run(SendAsync); }
            };
            composerFrame.Children.Add(_composer);
            var toolbar = new Grid(); toolbar.ColumnDefinitions.Add(new() { Width = new GridLength(1, GridUnitType.Star) }); toolbar.ColumnDefinitions.Add(new() { Width = GridLength.Auto });
            var tools = Row(4);
            var attach = Button(T("attachFiles"), () => Run(() => PickFilesAsync(false)), "\uE723", "AttachButton"); attach.IsEnabled = _state.CanAttach;
            var photo = Button(T("sendPhoto"), () => Run(() => PickFilesAsync(true)), "\uEB9F"); photo.IsEnabled = _state.CanAttach;
            _attachFileButton=attach;_attachPhotoButton=photo;
            var emoji = Button(T("emoji"), ShowEmoji, "\uE76E");
            tools.Children.Add(attach); tools.Children.Add(photo); tools.Children.Add(emoji);
            _characterCount = Text("", 11, true); _characterCount.VerticalAlignment = VerticalAlignment.Center; _characterCount.Margin = new Thickness(9, 0, 0, 0); tools.Children.Add(_characterCount);
            toolbar.Children.Add(tools);
            _send = Button(T("send"), () => Run(SendAsync), "\uE724", "SendButton");
            _send.Style = Application.Current.Resources["AccentButtonStyle"] as Style;
            Grid.SetColumn(_send, 1); toolbar.Children.Add(_send); Grid.SetRow(toolbar, 1); composerFrame.Children.Add(toolbar);
            Grid.SetRow(composerFrame, 2); _chatPanel.Children.Add(composerFrame);
        }
        _chatTitle!.Text = peer.Name;
        if(_attachFileButton is not null)_attachFileButton.IsEnabled=_state.CanAttach;
        if(_attachPhotoButton is not null)_attachPhotoButton.IsEnabled=_state.CanAttach;
        if(_reconnectButton is not null)_reconnectButton.Visibility=_state.CanSend?Visibility.Collapsed:Visibility.Visible;
        _chatSubtitle!.Text = peer.Id == _state.ConnectedPeerId ? T("connected") + " · " + TransportName(_state.Transport) : T("offlineHistory");
        _chatSubtitle.Foreground = peer.Id == _state.ConnectedPeerId ? AccentBrush() : null;
        if (!_draftDirty && !_sending && _composer!.Text != _state.Draft) { _applyingDraft = true; _composer.Text = _state.Draft; _applyingDraft = false; }
        UpdateComposer();
        RenderMessages();
        Run(RefreshHistoryStatusesAsync);
    }

    private void UpdateComposer()
    {
        if (_composer is null) return;
        var bytes = TextInput.Bytes(_composer.Text);
        var limit = _state.TextLimit > 0 ? _state.TextLimit : 65536;
        if (_characterCount is not null) _characterCount.Text = bytes < 0 ? T("textIncomplete") : bytes > limit * .75 ? $"{bytes:N0} / {limit:N0}" : T("enterToSend");
        if (_send is not null) _send.IsEnabled = !_sending && _state.CanSend && TextInput.CanSend(_composer.Text,limit);
    }

    private async Task SaveDraftAsync()
    {
        if (_composer is null || !_draftDirty || string.IsNullOrEmpty(_composerPeer)) return;
        var peer = _composerPeer; var text = _composer.Text; var revision = _draftRevision;
        await Call("draft", new { peerId = peer, text });
        if (_composerPeer == peer && _draftRevision == revision) _draftDirty = false;
    }

    private async Task SendAsync()
    {
        if (_composer is null || _sending || !_state.CanSend || string.IsNullOrWhiteSpace(_composer.Text) || string.IsNullOrEmpty(SelectedPeer)) return;
        var text = _composer.Text; var peer = SelectedPeer; var revision = _draftRevision;
        if(!TextInput.CanSend(text,_state.TextLimit))return;
        _sending = true; _draftTimer.Stop(); UpdateComposer();
        try
        {
            await Call("draft", new { peerId = peer, text });
            // Navigation may flush a draft while send is pending. Once the submitted
            // revision is saved, do not enqueue it again behind the send that clears it.
            if (_composerPeer == peer && _draftRevision == revision) _draftDirty = false;
            if (_cachedDraftPeer == peer && _cachedDraftRevision == revision) _cachedDraftDirty = false;
            await Call("send", new { peerId = peer, text });
            if (_cachedDraftPeer == peer && _cachedDraftRevision == revision)
            {
                _cachedDraftText = "";
                // Keep the acknowledged empty draft authoritative until a normal
                // flush, even if an older snapshot is still queued on the UI thread.
                _cachedDraftDirty = true;
            }
            if (_composerPeer == peer && _composer is not null)
            {
                if (_draftRevision == revision)
                {
                    _applyingDraft = true; _composer.Text = ""; _applyingDraft = false; _draftDirty = true;
                    _draftTimer.Stop(); _draftTimer.Start();
                }
                else { _draftDirty = true; await SaveDraftAsync(); }
            }
        }
        finally { _sending = false; UpdateComposer(); }
    }

    private void RenderMessages()
    {
        if (_messageStack is null || _messages is null) return;
        if (_historyPeer != SelectedPeer || _historyQuery != _messageQuery)
        {
            _historyMessages.Clear(); _historyPeer=SelectedPeer; _historyQuery=_messageQuery; _historyPaged=false;
            _historyHasMore=false; _historyBefore=null; _historyEpoch++;
            if (_messageQuery.Length > 0) Run(LoadSearchAsync);
        }
        foreach(var message in _state.Messages.Where(MatchesMessageQuery)) _historyMessages[MessageKey(message.Id, message.Outgoing)]=message;
        if (!_historyPaged && _messageQuery.Length == 0) { _historyHasMore=_state.MessagesHasMore; _historyBefore=_state.MessagesBefore; }
        var history = _historyMessages.Values.OrderBy(m=>m.Time).ToArray();
        var signature = string.Join("\n", history.Select(m => $"{m.Id}|{m.Body}|{m.Status}|{m.Attachment?.Version}|{m.Attachment?.State}|{m.Attachment?.Transferred}|{m.Attachment?.Path}|{m.Attachment?.RetryState}")) + _messageQuery + _historyBefore + _state.Generation;
        if (signature == _renderedMessages) return;
        _renderedMessages = signature;
        var atBottom = _messages.ScrollableHeight - _messages.VerticalOffset < 90;
        var offset = _messages.VerticalOffset;
        _messageStack.Children.Clear(); _incomingBodies.Clear();
        var matches = history.Where(MatchesMessageQuery).ToArray();
        if (_historyHasMore) _messageStack.Children.Add(Button(T("loadOlder"),()=>Run(LoadOlderAsync),id:"LoadOlder"));
        if (_messageQuery.Length > 0)
        {
            var reset = Button(T("clearSearch") + " · " + _messageQuery, () => { _messageQuery = ""; _renderedMessages = ""; RenderMessages(); }, "\uE721");
            _messageStack.Children.Add(reset);
        }
        if (matches.Length == 0)
        {
            var empty = Text(T(_messageQuery.Length > 0 ? "noResults" : "conversationStart"), 13, true); empty.HorizontalAlignment = HorizontalAlignment.Center; empty.Margin = new Thickness(0, 50, 0, 30); _messageStack.Children.Add(empty);
        }
        foreach (var message in matches)
        {
            var outer = Stack(6); outer.HorizontalAlignment = message.Outgoing ? HorizontalAlignment.Right : HorizontalAlignment.Left; outer.MaxWidth = 570;
            if (message.Attachment is null && !string.IsNullOrEmpty(message.Body))
            {
                var body = Text(message.Body, 15); body.IsTextSelectionEnabled = true;
                var bubble = new Border { Child = body, Padding = new Thickness(16, 12, 16, 12), CornerRadius = new CornerRadius(12), Background = message.Outgoing ? new SolidColorBrush(IsDark ? ColorHelper.FromArgb(255, 28, 67, 86) : ColorHelper.FromArgb(255, 225, 240, 249)) : CardBrush(), BorderBrush = message.Outgoing ? null : LineBrush(), BorderThickness = message.Outgoing ? new Thickness(0) : new Thickness(1) };
                outer.Children.Add(bubble);
                if (!message.Outgoing) _incomingBodies[message.Id] = body;
            }
            if (message.Attachment is { } attachment) outer.Children.Add(AttachmentCard(message, attachment));
            var status = Text(Time(message.Time) + (message.Outgoing ? "   " + StatusName(message.Status) : ""), 11, true); status.HorizontalAlignment = message.Outgoing ? HorizontalAlignment.Right : HorizontalAlignment.Left; status.Margin = new Thickness(5, 0, 5, 0); outer.Children.Add(status);
            _messageStack.Children.Add(outer);
        }
        _messageStack.UpdateLayout();
        _messages.ChangeView(null, atBottom ? _messages.ScrollableHeight : offset, null, true);
    }

    private async Task SearchMessagesAsync()
    {
        var input = new TextBox { Text = _messageQuery, PlaceholderText = T("searchMessages"), MinWidth = 320 };
        if (await DialogAsync(T("searchMessages"), input, T("search")) == ContentDialogResult.Primary) { _messageQuery = input.Text; _renderedMessages = ""; RenderMessages(); }
    }
    private async Task ClearHistoryAsync()
    {
        var peer = SelectedPeer;
        if (peer is not null && await DialogAsync(T("clearHistory"), Text(T("clearHistoryBody")), T("clear")) == ContentDialogResult.Primary) { await Call("clear", new { peerId = peer }); _historyMessages.Clear(); _historyPaged=false; _historyHasMore=false; _historyBefore=null; _historyEpoch++; _renderedMessages=""; await Call("refresh"); }
    }
    private async Task LoadOlderAsync()
    {
        var peer=SelectedPeer; var before=_historyBefore; var query=_messageQuery; var epoch=_historyEpoch;if(peer is null||before is null)return;
        var page=await _bridge.CallAsync<MessagesPage>("messages",new {peerId=peer,before,limit=20,query});
        if(peer!=SelectedPeer||epoch!=_historyEpoch||query!=_messageQuery||page is null)return;
        foreach(var message in page.Messages)_historyMessages[MessageKey(message.Id,message.Outgoing)]=message;
        _historyPaged=true;_historyHasMore=page.HasMore;_historyBefore=page.NextBefore;_renderedMessages="";RenderMessages();
    }
    private bool MatchesMessageQuery(Message message) => _messageQuery.Length == 0 || message.Body.Contains(_messageQuery, StringComparison.OrdinalIgnoreCase) || (message.Attachment?.Name.Contains(_messageQuery, StringComparison.OrdinalIgnoreCase) ?? false);
    private static string MessageKey(string id, bool outgoing) => id + ":" + (outgoing ? "true" : "false");

    private async Task LoadSearchAsync()
    {
        var peer=SelectedPeer; var query=_messageQuery; var epoch=_historyEpoch;
        if(peer is null || query.Length==0)return;
        var page=await _bridge.CallAsync<MessagesPage>("messages",new {peerId=peer,query,limit=20});
        if(peer!=SelectedPeer||epoch!=_historyEpoch||query!=_messageQuery||page is null)return;
        foreach(var message in page.Messages)_historyMessages[MessageKey(message.Id,message.Outgoing)]=message;
        _historyPaged=true;_historyHasMore=page.HasMore;_historyBefore=page.NextBefore;_renderedMessages="";RenderMessages();
    }

    private async Task RefreshHistoryStatusesAsync()
    {
        if(_refreshingHistory) { _historyRefreshAgain=true; return; }
        if(_historyMessages.Count==0 || SelectedPeer is not {} peer)return;
        var epoch=_historyEpoch;
        var newest=_state.Messages.Select(m=>MessageKey(m.Id,m.Outgoing)).ToHashSet();
        var keys=_historyMessages.Keys.Where(key=>!newest.Contains(key)).ToArray();
        if(keys.Length==0)return;
        _refreshingHistory=true;
        try
        {
            foreach(var batch in keys.Chunk(128))
            {
                var updates=await _bridge.CallAsync<MessageUpdate[]>("messageUpdates",new {peerId=peer,keys=batch});
                if(peer!=SelectedPeer||epoch!=_historyEpoch)return;
                foreach(var update in updates??[])
                {
                    var key=MessageKey(update.Id,update.Outgoing);
                    if(_historyMessages.TryGetValue(key,out var previous))_historyMessages[key]=new Message{Id=previous.Id,Body=previous.Body,Time=previous.Time,Outgoing=previous.Outgoing,Status=update.Status,Attachment=update.Attachment};
                }
            }
            RenderMessages();
        }
        finally
        {
            _refreshingHistory=false;
            if(_historyRefreshAgain) { _historyRefreshAgain=false; Run(RefreshHistoryStatusesAsync); }
        }
    }
    private void ShowEmoji()
    {
        if (_composer is null) return;
        var target=_composer; var panel=Stack(10); panel.Width=350;
        var search=new TextBox{PlaceholderText=T("emojiSearch")};panel.Children.Add(search);
        var category=new ComboBox{HorizontalAlignment=HorizontalAlignment.Stretch};
        var categories=new[]{"recent","Smileys & Emotion","People & Body","Animals & Nature","Food & Drink","Travel & Places","Activities","Objects","Symbols","Flags"};
        category.Items.Add(T("emojiRecent"));for(int i=0;i<9;i++)category.Items.Add(T("emojiCategory"+i));category.SelectedIndex=0;panel.Children.Add(category);
        var grid=new GridView{SelectionMode=ListViewSelectionMode.None,IsItemClickEnabled=true,MaxHeight=310};
        grid.ItemsPanel=(ItemsPanelTemplate)Microsoft.UI.Xaml.Markup.XamlReader.Load("<ItemsPanelTemplate xmlns='http://schemas.microsoft.com/winfx/2006/xaml/presentation'><ItemsWrapGrid Orientation='Horizontal'/></ItemsPanelTemplate>");
        panel.Children.Add(grid);var flyout=new Flyout{Content=panel}; int request=0;
        async Task Reload(){ int revision=++request;var items=await _bridge.CallAsync<EmojiItem[]>("emoji",new{category=categories[Math.Max(0,category.SelectedIndex)],query=search.Text});if(revision!=request)return;grid.Items.Clear();foreach(var item in items??[]){var tile=new GridViewItem{Tag=item,Content=new TextBlock{Text=item.Text,FontFamily=new FontFamily("Segoe UI Emoji"),FontSize=24},Padding=new Thickness(8)};ToolTipService.SetToolTip(tile,item.Label);AutomationProperties.SetName(tile,item.Label);grid.Items.Add(tile);} }
        search.TextChanged+=(_,_)=>Run(Reload);category.SelectionChanged+=(_,_)=>Run(Reload);
        grid.ItemClick+=(_,e)=>{if(e.ClickedItem is not GridViewItem tile||tile.Tag is not EmojiItem item)return;target.SelectedText=item.Text;flyout.Hide();target.Focus(FocusState.Programmatic);Run(()=>Call("emojiRecent",new{text=item.Text}));};
        flyout.Opening+=(_,_)=>{_modalDepth++;ResetDwell();};flyout.Closed+=(_,_)=>{_modalDepth--;ResetDwell();};
        flyout.ShowAt(target);Run(Reload);
    }
    private sealed class EmojiItem{public EmojiItem(){} public string Text{get;init;}="";public string Label{get;init;}="";}
}
