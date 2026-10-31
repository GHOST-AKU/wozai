using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media.Imaging;
using System.Text.RegularExpressions;
using Windows.Storage;
using Windows.Storage.Pickers;
using Windows.Storage.Streams;
using Windows.System;

namespace WoZai.Next;

public sealed partial class MainWindow
{
    private Border AttachmentCard(Message message, Attachment attachment)
    {
        var peer = SelectedPeer!;
        var generation = _state.Generation;
        var panel = Stack(9); panel.MinWidth = 250; panel.MaxWidth = 440;
        var title = Row(10); title.Children.Add(new FontIcon { Glyph = attachment.Mime.StartsWith("image/") ? "\uEB9F" : "\uE8A5", FontSize = 24, Foreground = AccentBrush(), VerticalAlignment = VerticalAlignment.Center });
        var labels = Stack(4); var name = Text(DisplayName(attachment.Name), 14); name.FontWeight = Microsoft.UI.Text.FontWeights.SemiBold; labels.Children.Add(name); labels.Children.Add(Text(Size(attachment.Size) + " · " + StatusName(attachment.State), 12, true)); title.Children.Add(labels); panel.Children.Add(title);
        var state = attachment.State.ToLowerInvariant();
        var complete = state is "delivered" or "received";
        var offered = state is "offered" or "offering" or "waiting" or "pending" or "invited";
        var failed = state is "failed" or "cancelled" or "canceled" or "rejected";
        var paused = state is "paused" or "sourceunavailable" or "interrupted" or "unknown";
        if (!complete && !offered && !failed)
        {
            panel.Children.Add(new ProgressBar { Minimum = 0, Maximum = Math.Max(1, attachment.Size), Value = Math.Min(attachment.Transferred, Math.Max(1, attachment.Size)), Height = 4 });
            panel.Children.Add(Text(Size(attachment.Transferred) + " / " + Size(attachment.Size), 11, true));
        }
        if (complete && attachment.Mime.StartsWith("image/", StringComparison.OrdinalIgnoreCase))
        {
            var preview = new Image { MaxWidth = 320, MaxHeight = 210, Stretch = Microsoft.UI.Xaml.Media.Stretch.Uniform, HorizontalAlignment = HorizontalAlignment.Left };
            panel.Children.Add(preview);
            Run(async () =>
            {
                try
                {
                    var path = await _bridge.CallAsync<string>("openPath", new { peerId = peer, id = attachment.Id, outgoing = attachment.Outgoing });
                    preview.Source = await BitmapFromFileAsync(path ?? throw new InvalidOperationException(T("attachmentUnavailable")));
                }
                catch { /* A missing local file keeps its explicit retry/export controls. */ }
            });
        }
        var controls = new VariableSizedWrapGrid { Orientation = Orientation.Horizontal, MaximumRowsOrColumns = 3 };
        void Action(string label, string action)
        {
            var button = Button(T(label), () => Run(async () =>
            {
                await Call("attachment", new { peerId = peer, id = attachment.Id, outgoing = attachment.Outgoing, action, generation });
            })); button.Margin = new Thickness(0, 0, 5, 5); controls.Children.Add(button);
        }
        if (!attachment.Outgoing && offered) { Action("accept", "accept"); Action("reject", "reject"); }
        else if (!complete && !failed && attachment.Version == 2)
        {
            Action(paused ? "resume" : "pause", paused ? "resume" : "pause"); Action("cancel", "cancel");
        }
        if (attachment.Outgoing && state == "failed" && attachment.Version == 2 && attachment.RetryState is not ("retrying" or "retried" or "ineligible"))
        {
            Action("retry", "retry");
            var reselect = Button(T("reselect"), () => Run(() => ReselectAsync(peer, attachment))); reselect.Margin = new Thickness(0, 0, 5, 5); controls.Children.Add(reselect);
        }
        if (attachment.HasRetryableBatch) Action("retryBatch", "retryBatch");
        if (complete)
        {
            if (attachment.Mime.StartsWith("image/", StringComparison.OrdinalIgnoreCase) || attachment.SafeToOpen)
            {
                var open = Button(T(attachment.Mime.StartsWith("image/") ? "viewImage" : "open"), () => Run(() => OpenAttachmentAsync(peer, attachment))); open.Margin = new Thickness(0, 0, 5, 5); controls.Children.Add(open);
            }
            var export = Button(T("saveAs"), () => Run(() => ExportAttachmentAsync(peer, attachment))); export.Margin = new Thickness(0, 0, 5, 5); controls.Children.Add(export);
            var folder = Button(T("showInFolder"), () => Run(async () =>
            {
                var path = await _bridge.CallAsync<string>("openPath", new { peerId = peer, id = attachment.Id, outgoing = attachment.Outgoing });
                var file = await StorageFile.GetFileFromPathAsync(path);
                var options = new FolderLauncherOptions(); options.ItemsToSelect.Add(file);
                await Launcher.LaunchFolderAsync(await file.GetParentAsync(), options);
            })); folder.Margin = new Thickness(0, 0, 5, 5); controls.Children.Add(folder);
        }
        panel.Children.Add(controls);
        if (!string.IsNullOrEmpty(attachment.Route)) panel.Children.Add(Text(attachment.Route, 10, true));
        if (!string.IsNullOrEmpty(attachment.Path)) { var location = Text(attachment.Path, 10, true); location.IsTextSelectionEnabled = true; panel.Children.Add(location); }
        return Card(panel, new Thickness(14));
    }

    private FileOpenPicker FilePicker(bool images)
    {
        var picker = new FileOpenPicker { SuggestedStartLocation = PickerLocationId.PicturesLibrary, ViewMode = images ? PickerViewMode.Thumbnail : PickerViewMode.List };
        WinRT.Interop.InitializeWithWindow.Initialize(picker, _hwnd);
        foreach (var extension in images ? new[] { ".png", ".jpg", ".jpeg", ".webp", ".gif", ".bmp" } : new[] { "*" }) picker.FileTypeFilter.Add(extension);
        return picker;
    }
    private async Task PickFilesAsync(bool images)
    {
        var peer = SelectedPeer;
        var generation = _state.Generation;
        if (peer is null || !_state.CanAttach) return;
        var files = await FilePicker(images).PickMultipleFilesAsync();
        if (files.Count == 0) return;
        RequireSession(peer, generation);
        var selected = files.ToList();
        var review = Stack(12); review.MinWidth = 360; review.Children.Add(Text(T("fileReviewBody"), 13, true));
        var count = Text("", 12, true); review.Children.Add(count);
        async Task AddFile(StorageFile file)
        {
            var properties = await file.GetBasicPropertiesAsync();
            var card = Stack(9);
            if (file.ContentType.StartsWith("image/"))
            {
                try { card.Children.Add(new Image { Source = await BitmapFromFileAsync(file.Path), Height = 130, MaxWidth = 360, Stretch = Microsoft.UI.Xaml.Media.Stretch.Uniform, HorizontalAlignment = HorizontalAlignment.Left }); } catch { }
            }
            card.Children.Add(Text(DisplayName(file.Name), 14)); card.Children.Add(Text(Size((long)properties.Size), 12, true));
            var border = Card(card, new Thickness(12));
            card.Children.Add(Button(T("remove"), () => { selected.Remove(file); review.Children.Remove(border); count.Text = T("selectedFiles") + " " + selected.Count; }));
            review.Children.Add(border);
        }
        foreach (var file in files) await AddFile(file);
        count.Text = T("selectedFiles") + " " + selected.Count;
        if (await DialogAsync(T(images ? "sendPhoto" : "attachFiles"), review, T("send")) == ContentDialogResult.Primary && selected.Count > 0)
        {
            RequireSession(peer, generation);
            await Call("files", new { peerId = peer, generation, paths = selected.Select(f => f.Path).ToArray() });
        }
    }
    private async Task ReselectAsync(string peer, Attachment attachment)
    {
        var generation = _state.Generation;
        RequireSession(peer, generation);
        var file = await FilePicker(false).PickSingleFileAsync();
        if (file is null) return;
        RequireSession(peer, generation);
        var panel = Stack(); panel.Children.Add(Text(DisplayName(file.Name), 16)); panel.Children.Add(Text(T("replacementBody"), 13, true));
        if (await DialogAsync(T("reselect"), panel, T("retry")) != ContentDialogResult.Primary) return;
        RequireSession(peer, generation);
        await Call("retryFile", new { peerId = peer, generation, id = attachment.Id, path = file.Path });
    }
    private async Task ExportAttachmentAsync(string peer, Attachment attachment)
    {
        var destination = await SavePickerAsync(attachment.Name);
        if (destination is not null) await Call("export", new { peerId = peer, id = attachment.Id, outgoing = attachment.Outgoing, destination = destination.Path });
    }
    private async Task<StorageFile?> SavePickerAsync(string name)
    {
        name = SafeFileName(name);
        var extension = System.IO.Path.GetExtension(name);
        if (!Regex.IsMatch(extension, @"^\.[A-Za-z0-9]{1,12}$")) extension = ".bin";
        var picker = new FileSavePicker { SuggestedStartLocation = PickerLocationId.Downloads, SuggestedFileName = name };
        picker.FileTypeChoices.Add(T("file"), new List<string> { extension });
        WinRT.Interop.InitializeWithWindow.Initialize(picker, _hwnd);
        return await picker.PickSaveFileAsync();
    }
    private async Task OpenAttachmentAsync(string peer, Attachment attachment)
    {
        var path = await _bridge.CallAsync<string>("openPath", new { peerId = peer, id = attachment.Id, outgoing = attachment.Outgoing });
        if (attachment.Mime.StartsWith("image/", StringComparison.OrdinalIgnoreCase))
        {
            var image = new Image { Source = await BitmapFromFileAsync(path ?? throw new InvalidOperationException(T("attachmentUnavailable"))), Stretch = Microsoft.UI.Xaml.Media.Stretch.Uniform, MaxWidth = 640, MaxHeight = Math.Max(180, _root.ActualHeight - 310) };
            var viewer = new ScrollViewer {Content=image,ZoomMode=ZoomMode.Enabled,MinZoomFactor=.25f,MaxZoomFactor=8,Height=Math.Max(180,_root.ActualHeight-350),Width=640,HorizontalScrollBarVisibility=ScrollBarVisibility.Auto};
            var panel = Stack(12); panel.Children.Add(viewer);
            var zoom=Row();zoom.Children.Add(Button("−",()=>viewer.ChangeView(null,null,Math.Max(.25f,viewer.ZoomFactor/1.25f))));zoom.Children.Add(Button("+",()=>viewer.ChangeView(null,null,Math.Min(8,viewer.ZoomFactor*1.25f))));panel.Children.Add(zoom);
            panel.Children.Add(Text(DisplayName(attachment.Name) + " · " + Size(attachment.Size), 12, true));
            if (await DialogAsync(T("viewImage"), panel, T("saveAs")) == ContentDialogResult.Primary) await ExportAttachmentAsync(peer, attachment);
        }
        else
        {
            // The kernel evaluates its extension allowlist; unknown/executable files only export.
            if (!attachment.SafeToOpen) throw new InvalidOperationException(T("unsafeOpen"));
            var file = await StorageFile.GetFileFromPathAsync(path);
            if (!await Launcher.LaunchFileAsync(file)) throw new InvalidOperationException(T("noFileApp"));
        }
    }
    private async Task ShowQueueAsync()
    {
        var peer = SelectedPeer; if (peer is null) return;
        var panel = Stack(14); panel.MinWidth = 360;
        var actions = Row();
        foreach (var action in new[] { "pause", "resume", "cancel" }) actions.Children.Add(Command(T(action), "batch", new { peerId = peer, action })); panel.Children.Add(actions);
        if (_state.BatchItems.Length == 0) panel.Children.Add(Text(T("emptyQueue"), 13, true));
        foreach (var item in _state.BatchItems)
        {
            var row = Stack(5); row.Children.Add(Text(DisplayName(item.Name), 14)); row.Children.Add(Text(StatusName(item.State) + " · " + Size(item.Transferred) + " / " + Size(item.Size), 12, true));
            if (item.Size > 0) row.Children.Add(new ProgressBar { Maximum = item.Size, Value = Math.Min(item.Transferred, item.Size) }); panel.Children.Add(Card(row, new Thickness(12)));
        }
        await DialogAsync(T("batchQueue"), panel);
    }
    private static async Task<BitmapImage> BitmapFromFileAsync(string path)
    {
        var file = await StorageFile.GetFileFromPathAsync(path);
        using var stream = await file.OpenReadAsync();
        var bitmap = new BitmapImage { DecodePixelWidth = 1200 };
        await bitmap.SetSourceAsync(stream); return bitmap;
    }
    private static async Task<BitmapImage> BitmapFromPngAsync(string png)
    {
        var bytes = Convert.FromBase64String(png);
        using var stream = new InMemoryRandomAccessStream();
        using (var writer = new DataWriter(stream.GetOutputStreamAt(0))) { writer.WriteBytes(bytes); await writer.StoreAsync(); }
        stream.Seek(0); var bitmap = new BitmapImage(); await bitmap.SetSourceAsync(stream); return bitmap;
    }
    private static async Task<Image> ImageFromPngAsync(string png, double size) => new() { Source = await BitmapFromPngAsync(png), Width = size, Height = size, Stretch = Microsoft.UI.Xaml.Media.Stretch.Uniform, HorizontalAlignment = HorizontalAlignment.Left };
    private static string DisplayName(string value) => Regex.Replace(value, "[\\u061c\\u200e\\u200f\\u202a-\\u202e\\u2066-\\u2069]", "");
    private static string SafeFileName(string value)
    {
        var clean = Regex.Replace(DisplayName(value), "[\\\\/\\x00-\\x1f:*?\"<>|]", "_").TrimEnd('.', ' ');
        return string.IsNullOrWhiteSpace(clean) || Regex.IsMatch(clean, @"^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\..*)?$", RegexOptions.IgnoreCase) ? "attachment" : clean;
    }
}
