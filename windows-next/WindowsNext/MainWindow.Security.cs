using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Windows.Graphics.Imaging;
using Windows.Storage;
using Windows.Storage.Streams;

namespace WoZai.Next;

public sealed partial class MainWindow
{
    private Border ProfileAvatar(double size)
    {
        var border = InitialAvatar(_state.Nickname, size);
        Run(async () => {
            var avatar = await _bridge.CallAsync<Avatar>("avatar");
            if (!string.IsNullOrEmpty(avatar?.Png)) border.Child = await ImageFromPngAsync(avatar.Png, size);
        });
        return border;
    }
    private async Task ShowSecurityAsync()
    {
        var peer = SelectedPeer;
        if (peer is null) return;
        var verification = await _bridge.CallAsync<Verification>("verification", new { peerId = peer });
        if (verification is null) { await DialogAsync(T("security"), Text(T("connectForVerification"))); return; }
        var generation = verification.Generation;
        var panel = Stack(14); panel.MinWidth = 340;
        panel.Children.Add(Text(T(verification.Verified ? "verified" : "unverified"), 17));
        panel.Children.Add(Text(T("verifyExplain"), 13));
        var code = Text(verification.Display, 17); code.FontFamily = new Microsoft.UI.Xaml.Media.FontFamily("Consolas"); code.IsTextSelectionEnabled = true; panel.Children.Add(code);
        var png = await _bridge.CallAsync<string>("qrImage", new { peerId = peer });
        if (png is not null) panel.Children.Add(await ImageFromPngAsync(png, 240));
        panel.Children.Add(Button(T("copyCode"), () => Copy(verification.Display)));
        panel.Children.Add(Button(T("saveQr"), () => Run(async () => {
            RequireSession(peer,generation);
            var destination = await SavePickerAsync("WoZai-identity.png");
            if (destination is not null && png is not null) { RequireSession(peer,generation); await FileIO.WriteBytesAsync(destination,Convert.FromBase64String(png)); }
        })));
        // Only after the first dialog closes may the same XamlRoot show confirmation.
        var result = await DialogAsync(T("security"), panel, T("markVerified"), T("readQr"));
        if (result == ContentDialogResult.None) return;
        RequireSession(peer,generation);
        string? scanned = null;
        if (result == ContentDialogResult.Secondary) {
            var file = await FilePicker(true).PickSingleFileAsync();
            if (file is null) return;
            RequireSession(peer,generation);
            scanned = await _bridge.CallAsync<string>("readQr",new { path=file.Path });
            if (scanned != verification.QrPayload) { ReportError(T("qrMismatch")); return; }
        }
        if (await DialogAsync(T("security"),Text(T("verifyConfirm")),T("markVerified")) == ContentDialogResult.Primary) {
            RequireSession(peer,generation);
            await Call("verify",new { peerId=peer,generation,scanned });
            ShowNotice(T("verificationDone"));
        }
    }

    private async Task EditAvatarAsync()
    {
        var avatar = await _bridge.CallAsync<Avatar>("avatar") ?? new Avatar();
        var panel = Stack(14); panel.MinWidth = 350;
        var preview = new Image { Width=128, Height=128, HorizontalAlignment=HorizontalAlignment.Left };
        if (!string.IsNullOrEmpty(avatar.Png)) preview.Source=await BitmapFromPngAsync(avatar.Png);
        panel.Children.Add(preview); panel.Children.Add(Text(T("avatarCropHint"),13,true));
        StorageFile? source=null; byte[]? cropped=null; int revision=0;
        var zoom=new Slider { Minimum=1,Maximum=3,Value=1,Header=T("zoom"),Width=330 };
        var horizontal=new Slider { Minimum=0,Maximum=1,Value=.5,Header=T("horizontal"),Width=330 };
        var vertical=new Slider { Minimum=0,Maximum=1,Value=.5,Header=T("vertical"),Width=330 };
        async Task Crop() {
            var file=source; if(file is null)return;
            int run=++revision; double z=zoom.Value,x=horizontal.Value,y=vertical.Value;
            using var input=await file.OpenReadAsync();
            if(input.Size>32*1024*1024)throw new InvalidOperationException(T("attachmentTooLarge"));
            var decoder=await BitmapDecoder.CreateAsync(input);
            if((long)decoder.PixelWidth*decoder.PixelHeight>40_000_000)throw new InvalidOperationException(T("attachmentTooLarge"));
            double ratio=128d/Math.Min(decoder.PixelWidth,decoder.PixelHeight)*z;
            uint w=Math.Max(128,(uint)Math.Ceiling(decoder.PixelWidth*ratio)),h=Math.Max(128,(uint)Math.Ceiling(decoder.PixelHeight*ratio));
            using var output=new InMemoryRandomAccessStream();
            var encoder=await BitmapEncoder.CreateForTranscodingAsync(output,decoder);
            encoder.BitmapTransform.ScaledWidth=w;encoder.BitmapTransform.ScaledHeight=h;
            encoder.BitmapTransform.Bounds=new BitmapBounds {X=(uint)((w-128)*x),Y=(uint)((h-128)*y),Width=128,Height=128};
            encoder.IsThumbnailGenerated=false;
            await encoder.FlushAsync();
            // Transcoding preserves the source codec. Convert the cropped pixels to PNG.
            output.Seek(0); var croppedDecoder=await BitmapDecoder.CreateAsync(output);
            var pixels=await croppedDecoder.GetPixelDataAsync(BitmapPixelFormat.Bgra8,BitmapAlphaMode.Straight,new BitmapTransform(),ExifOrientationMode.IgnoreExifOrientation,ColorManagementMode.DoNotColorManage);
            using var pngStream=new InMemoryRandomAccessStream(); var pngEncoder=await BitmapEncoder.CreateAsync(BitmapEncoder.PngEncoderId,pngStream);
            pngEncoder.SetPixelData(BitmapPixelFormat.Bgra8,BitmapAlphaMode.Straight,128,128,96,96,pixels.DetachPixelData());await pngEncoder.FlushAsync();
            pngStream.Seek(0);using var reader=new DataReader(pngStream.GetInputStreamAt(0));await reader.LoadAsync((uint)pngStream.Size);
            var data=new byte[(int)pngStream.Size];reader.ReadBytes(data);
            if(run!=revision)return;cropped=data;preview.Source=await BitmapFromPngAsync(Convert.ToBase64String(data));
        }
        panel.Children.Add(Button(T("choosePhoto"),()=>Run(async()=> { source=await FilePicker(true).PickSingleFileAsync();if(source is not null)await Crop(); })));
        foreach(var slider in new[]{zoom,horizontal,vertical}) {slider.ValueChanged+=(_,_)=>Run(Crop);panel.Children.Add(slider);}
        var result=await DialogAsync(T("editAvatar"),panel,T("save"),T("resetAvatar"));
        revision++;
        if(result==ContentDialogResult.Primary && cropped is not null)await Call("saveAvatar",new { revision=avatar.Revision,png=Convert.ToBase64String(cropped) });
        else if(result==ContentDialogResult.Secondary)await Call("resetAvatar",new { revision=avatar.Revision });
        if(_tab=="settings"){_page.Children.Clear();RenderSettings();}
    }
}
