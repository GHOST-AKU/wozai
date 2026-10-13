package dev.ghost.nearbyim;

import android.content.ContentResolver;
import android.graphics.*;
import android.net.Uri;
import android.os.Build;
import android.os.CancellationSignal;
import dev.ghost.nearbyim.core.AvatarSpec;
import dev.ghost.nearbyim.core.ImageOrientation;
import java.io.*;
import java.nio.ByteBuffer;

/** Offline, bounded first-frame decoding and fresh PNG encoding for avatar images. */
public final class AvatarImages {
    private AvatarImages() {}
    public static Bitmap decode(ContentResolver resolver, Uri source) throws IOException {
        return decode(resolver,source,new CancellationSignal());
    }
    public static Bitmap decode(ContentResolver resolver,Uri source,CancellationSignal cancellation)throws IOException {
        if (source == null || !"content".equals(source.getScheme())) throw new IOException("Unusable image source");
        try(android.content.res.AssetFileDescriptor descriptor=resolver.openAssetFileDescriptor(source,"r",cancellation)) {
            if(descriptor==null)throw new IOException("Image unavailable");
            try(InputStream input=descriptor.createInputStream()) {
                cancellation.setOnCancelListener(()->{Thread closer=new Thread(()->{try{input.close();}catch(IOException ignored){}},"avatar-close");closer.setDaemon(true);closer.start();});
                try{cancellation.throwIfCanceled();return decode(read(input));}finally{cancellation.setOnCancelListener(null);}
            }
        } catch (RuntimeException | OutOfMemoryError failure) { throw new IOException("Image unavailable", failure); }
    }
    private static byte[] read(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[32768]; int count;
        while ((count = input.read(buffer)) != -1) {
            interrupted();
            if ((long) bytes.size() + count > AvatarSpec.MAX_INPUT_BYTES) throw new IOException("Image source exceeds bound");
            bytes.write(buffer, 0, count);
        }
        return bytes.toByteArray();
    }
    public static Bitmap decode(byte[] encoded) throws IOException {
        if (encoded == null || encoded.length == 0 || encoded.length > AvatarSpec.MAX_INPUT_BYTES) throw new IOException("Image source exceeds bound");
        interrupted();
        try {
            BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(encoded, 0, encoded.length, options); dimensions(options.outWidth, options.outHeight);
            if(Build.VERSION.SDK_INT>=28&&("image/heif".equals(options.outMimeType)||"image/heic".equals(options.outMimeType)||"image/avif".equals(options.outMimeType)))return modern(encoded);
            options.inJustDecodeBounds = false; options.inPreferredConfig = Bitmap.Config.ARGB_8888; options.inSampleSize = 1;
            while ((Math.max(options.outWidth, options.outHeight) + options.inSampleSize - 1) / options.inSampleSize > AvatarSpec.MAX_DECODE_EDGE) options.inSampleSize *= 2;
            Bitmap bitmap = BitmapFactory.decodeByteArray(encoded, 0, encoded.length, options);
            if (bitmap == null) throw new IOException("Unsupported image");
            int orientation;try{orientation=ImageOrientation.read(new ByteArrayInputStream(encoded));interrupted();}catch(IOException failure){bitmap.recycle();throw failure;} Matrix transform = new Matrix();
            switch (orientation) {
                case 2: transform.setScale(-1, 1); break;
                case 3: transform.setRotate(180); break;
                case 4: transform.setScale(1, -1); break;
                case 5: transform.setRotate(90); transform.postScale(-1, 1); break;
                case 6: transform.setRotate(90); break;
                case 7: transform.setRotate(-90); transform.postScale(-1, 1); break;
                case 8: transform.setRotate(-90); break;
                default: return bitmap;
            }
            Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), transform, true);
            if (rotated != bitmap) bitmap.recycle(); return rotated;
        } catch (RuntimeException | OutOfMemoryError failure) { throw new IOException("Unsupported image", failure); }
    }
    @android.annotation.TargetApi(28)
    private static Bitmap modern(byte[] bytes) throws IOException {
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes)), (decoder, info, source) -> {
            int width = info.getSize().getWidth(), height = info.getSize().getHeight();
            try { dimensions(width, height); interrupted(); } catch (IOException rejected) { throw new IllegalArgumentException("Unsupported image dimensions", rejected); }
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE); decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB));
            int largest = Math.max(width, height);
            if (largest > AvatarSpec.MAX_DECODE_EDGE) decoder.setTargetSize(Math.max(1, width * AvatarSpec.MAX_DECODE_EDGE / largest), Math.max(1, height * AvatarSpec.MAX_DECODE_EDGE / largest));
            decoder.setOnPartialImageListener(error -> false);
        });
    }
    private static void dimensions(int width, int height) throws IOException {
        if (width < 1 || height < 1 || width > AvatarSpec.MAX_INPUT_EDGE || height > AvatarSpec.MAX_INPUT_EDGE || (long) width * height > AvatarSpec.MAX_INPUT_PIXELS) throw new IOException("Unsupported image dimensions");
    }
    /** Crop coordinates refer to the already oriented, bounded source pixels. */
    public static byte[] encodeCrop(Bitmap source, RectF crop) throws IOException {
        if (source == null || source.isRecycled() || crop == null || !Float.isFinite(crop.left) || !Float.isFinite(crop.top) || !Float.isFinite(crop.right) || !Float.isFinite(crop.bottom) || crop.width() < 1 || Math.abs(crop.width() - crop.height()) > .02f || crop.left < -.02f || crop.top < -.02f || crop.right > source.getWidth() + .02f || crop.bottom > source.getHeight() + .02f) throw new IOException("Invalid crop");
        for (int edge : AvatarSpec.OUTPUT_EDGES) {
            interrupted(); Bitmap output = Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888);
            try {
                Canvas canvas = new Canvas(output); Matrix matrix = new Matrix();
                matrix.setRectToRect(crop, new RectF(0, 0, edge, edge), Matrix.ScaleToFit.FILL);
                canvas.drawBitmap(source, matrix, new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG));
                ByteArrayOutputStream png = new ByteArrayOutputStream();
                if (!output.compress(Bitmap.CompressFormat.PNG, 100, png)) throw new IOException("Image encoding failed");
                interrupted(); if (png.size() <= AvatarSpec.MAX_BYTES) return png.toByteArray();
            } finally { output.recycle(); }
        }
        throw new IOException("Image encoding exceeds bound");
    }
    public static Bitmap preview(byte[] png) throws IOException {
        if (png == null || png.length > AvatarSpec.MAX_BYTES) throw new IOException("Invalid avatar image");
        BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(png, 0, png.length, bounds);
        if (bounds.outWidth < 1 || bounds.outWidth > AvatarSpec.MAX_EDGE || bounds.outWidth != bounds.outHeight || !"image/png".equals(bounds.outMimeType)) throw new IOException("Invalid avatar image");
        Bitmap bitmap = BitmapFactory.decodeByteArray(png, 0, png.length);
        if (bitmap == null) throw new IOException("Invalid avatar image"); return bitmap;
    }
    private static void interrupted() throws InterruptedIOException { if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Avatar operation canceled"); }
}
