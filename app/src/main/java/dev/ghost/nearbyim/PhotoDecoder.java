package dev.ghost.nearbyim;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import dev.ghost.nearbyim.core.ImageOrientation;
import android.media.MediaMetadataRetriever;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import java.io.IOException;
import java.io.InputStream;

/** Bounded image decoding shared by chat previews and the native photo viewer. */
public final class PhotoDecoder {
    private PhotoDecoder() {}
    public static Bitmap decode(ContentResolver resolver,Uri uri,int maximumSide)throws IOException {
        if(maximumSide<1||maximumSide>2048)throw new IllegalArgumentException("Invalid photo decoding bound");
        BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;
        try(InputStream input=open(resolver,uri)){BitmapFactory.decodeStream(input,null,options);}
        if(options.outWidth<=0||options.outHeight<=0||(long)options.outWidth*options.outHeight>100000000)throw new IOException("Unsupported photo dimensions");
        options.inJustDecodeBounds=false;options.inSampleSize=1;
        while((Math.max(options.outWidth,options.outHeight)+options.inSampleSize-1)/options.inSampleSize>maximumSide)options.inSampleSize*=2;
        Bitmap bitmap;try(InputStream input=open(resolver,uri)){bitmap=BitmapFactory.decodeStream(input,null,options);}
        if(bitmap==null)throw new IOException("Unsupported photo");
        int orientation=1;
        try {
            if(android.os.Build.VERSION.SDK_INT>=28&&("image/heif".equals(options.outMimeType)||"image/heic".equals(options.outMimeType)||"image/avif".equals(options.outMimeType)))orientation=heif(resolver,uri);
            else try(InputStream input=open(resolver,uri)){orientation=ImageOrientation.read(input);}
        }catch(IOException|RuntimeException ignored){} // Missing or invalid direction metadata leaves pixels unchanged.
        Matrix transform=new Matrix();
        switch(orientation){
            case 2:transform.setScale(-1,1);break;
            case 3:transform.setRotate(180);break;
            case 4:transform.setScale(1,-1);break;
            case 5:transform.setRotate(90);transform.postScale(-1,1);break;
            case 6:transform.setRotate(90);break;
            case 7:transform.setRotate(-90);transform.postScale(-1,1);break;
            case 8:transform.setRotate(-90);break;
            default:return bitmap;
        }
        Bitmap rotated=Bitmap.createBitmap(bitmap,0,0,bitmap.getWidth(),bitmap.getHeight(),transform,true);
        if(rotated!=bitmap)bitmap.recycle();return rotated;
    }
    // AOSP exposes these metadata keys from API 29, but HEIC extraction supports API 28.
    @android.annotation.SuppressLint("InlinedApi")
    @android.annotation.TargetApi(28)
    private static int heif(ContentResolver resolver,Uri uri)throws IOException {
        MediaMetadataRetriever reader=new MediaMetadataRetriever();
        try(AssetFileDescriptor file=resolver.openAssetFileDescriptor(uri,"r")){
            if(file==null)return 1;
            if(file.getDeclaredLength()<0)reader.setDataSource(file.getFileDescriptor());
            else reader.setDataSource(file.getFileDescriptor(),file.getStartOffset(),file.getDeclaredLength());
            String rotation=reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_IMAGE_ROTATION);
            int orientation="90".equals(rotation)?6:"180".equals(rotation)?3:"270".equals(rotation)?8:1;
            try{String start=reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_EXIF_OFFSET),size=reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_EXIF_LENGTH);
            if(start!=null&&size!=null){
                long offset=Long.parseLong(start),length=Long.parseLong(size);
                if(offset>=0&&offset<=dev.ghost.nearbyim.core.AttachmentInfo.MAX_SIZE&&length>0&&length<=ImageOrientation.MAX_EXIF_BYTES&&(file.getDeclaredLength()<0||offset<=file.getDeclaredLength()-length)){
                    try(InputStream input=open(resolver,uri)){ImageOrientation.skipBytes(input,offset);byte[] data=ImageOrientation.readBytes(input,(int)length);if(data.length==length)orientation=ImageOrientation.fromExif(data,orientation);}
                }
            }}catch(IOException|RuntimeException ignored){}
            return orientation;
        }finally{try{reader.release();}catch(IOException ignored){}}
    }
    private static InputStream open(ContentResolver resolver,Uri uri)throws IOException {
        InputStream input=resolver.openInputStream(uri);if(input==null)throw new IOException("Photo unavailable");return input;
    }
}
