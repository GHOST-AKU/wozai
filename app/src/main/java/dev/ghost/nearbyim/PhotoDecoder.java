package dev.ghost.nearbyim;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import androidx.exifinterface.media.ExifInterface;
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
        while(Math.max(options.outWidth,options.outHeight)/options.inSampleSize>maximumSide)options.inSampleSize*=2;
        Bitmap bitmap;try(InputStream input=open(resolver,uri)){bitmap=BitmapFactory.decodeStream(input,null,options);}
        if(bitmap==null)throw new IOException("Unsupported photo");
        int orientation=ExifInterface.ORIENTATION_NORMAL;
        try(InputStream input=open(resolver,uri)){orientation=new ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL);}
        catch(IOException ignored){} // Formats without EXIF still display normally.
        Matrix transform=new Matrix();
        switch(orientation){
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:transform.setScale(-1,1);break;
            case ExifInterface.ORIENTATION_ROTATE_180:transform.setRotate(180);break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:transform.setScale(1,-1);break;
            case ExifInterface.ORIENTATION_TRANSPOSE:transform.setRotate(90);transform.postScale(-1,1);break;
            case ExifInterface.ORIENTATION_ROTATE_90:transform.setRotate(90);break;
            case ExifInterface.ORIENTATION_TRANSVERSE:transform.setRotate(-90);transform.postScale(-1,1);break;
            case ExifInterface.ORIENTATION_ROTATE_270:transform.setRotate(-90);break;
            default:return bitmap;
        }
        Bitmap rotated=Bitmap.createBitmap(bitmap,0,0,bitmap.getWidth(),bitmap.getHeight(),transform,true);
        if(rotated!=bitmap)bitmap.recycle();return rotated;
    }
    private static InputStream open(ContentResolver resolver,Uri uri)throws IOException {
        InputStream input=resolver.openInputStream(uri);if(input==null)throw new IOException("Photo unavailable");return input;
    }
}
