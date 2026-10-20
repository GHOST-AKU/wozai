package dev.ghost.nearbyim;

import android.content.*;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.*;
import java.io.*;
import java.util.concurrent.*;

/** Synthetic image bytes and a gated pipe; absent from all client APKs. */
public final class AvatarTestProvider extends ContentProvider {
    private volatile CountDownLatch gate = new CountDownLatch(0);
    private volatile CountDownLatch started = new CountDownLatch(0);
    public boolean onCreate() { return true; }
    public Bundle call(String method, String argument, Bundle extras) {
        if (method.equals("hold")) { started = new CountDownLatch(1); gate = new CountDownLatch(1); }
        else if (method.equals("release")) gate.countDown();
        else if (method.equals("started")) { Bundle state = new Bundle(); state.putBoolean("started", started.getCount() == 0); return state; }
        else throw new IllegalArgumentException(); return new Bundle();
    }
    public String getType(Uri uri) { return "image/png"; }
    public AssetFileDescriptor openAssetFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Read only");
        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe(); CountDownLatch captured = gate, entered = started;
            new Thread(() -> {
                try (OutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                    if (uri.getPath().startsWith("/slow")) { entered.countDown(); captured.await(10, TimeUnit.SECONDS); }
                    Bitmap bitmap = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888);
                    bitmap.eraseColor(uri.getPath().endsWith("blue") ? 0xff0000ff : 0xffff0000);
                    try { bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); } finally { bitmap.recycle(); }
                } catch (IOException | InterruptedException ignored) {}
            }, "avatar-test-image").start();
            return new AssetFileDescriptor(pipe[0], 0, AssetFileDescriptor.UNKNOWN_LENGTH);
        } catch (IOException failure) { throw new FileNotFoundException(failure.toString()); }
    }
    public Cursor query(Uri uri, String[] projection, String selection, String[] arguments, String order) { return null; }
    public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    public int update(Uri uri, ContentValues values, String selection, String[] arguments) { throw new UnsupportedOperationException(); }
    public int delete(Uri uri, String selection, String[] arguments) { throw new UnsupportedOperationException(); }
}
