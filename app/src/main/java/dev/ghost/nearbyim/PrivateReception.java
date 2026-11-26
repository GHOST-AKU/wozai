package dev.ghost.nearbyim;

import android.content.Context;
import android.content.SharedPreferences;
import dev.ghost.nearbyim.transport.Peer;

/** Explicit per-transport opt-in. A manual listener does not opt the device into foreground restore. */
public final class PrivateReception {
    private PrivateReception() {}
    private static final String LAN = "restore-lan", BLUETOOTH = "restore-bluetooth";

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("private-reception", Context.MODE_PRIVATE);
    }
    private static String key(int mode) {
        return mode == Peer.LAN ? LAN : mode == Peer.BLUETOOTH ? BLUETOOTH : null;
    }
    public static boolean enabled(Context context, int mode) {
        String key = key(mode);
        return key != null && preferences(context).getBoolean(key, false);
    }
    public static void setEnabled(Context context, int mode, boolean enabled) {
        String key = key(mode);
        if (key != null) preferences(context).edit().putBoolean(key, enabled).apply();
    }
    public static void pauseAll(Context context) {
        preferences(context).edit().remove(LAN).remove(BLUETOOTH).apply();
    }
}
