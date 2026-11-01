package android.content;

import android.content.pm.PackageManager;

/** Host boundary double; no Android service is opened by these tests. */
public class Context {
    public Context getApplicationContext() { return this; }
    public int checkSelfPermission(String permission) { return PackageManager.PERMISSION_GRANTED; }
    public PackageManager getPackageManager() { throw new UnsupportedOperationException(); }
    public <T> T getSystemService(Class<T> type) { throw new UnsupportedOperationException(); }
}
