package dev.ghost.nearbyim;

import android.content.Context;
import android.content.res.Configuration;

/** A local UI preference; configuration overrides never restart the chat service. */
public final class AppAppearance {
    public static final String SYSTEM = "system", LIGHT = "light", DARK = "dark";
    private AppAppearance() {}

    private static String normalize(String value) {
        return LIGHT.equals(value) ? LIGHT : DARK.equals(value) ? DARK : SYSTEM;
    }

    public static String selection(Context context) {
        return normalize(context.getSharedPreferences("ui", Context.MODE_PRIVATE).getString("theme", SYSTEM));
    }

    public static void select(Context context, String value) {
        context.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putString("theme", normalize(value)).apply();
    }

    public static Context wrap(Context context) {
        String selected = selection(context);
        if (SYSTEM.equals(selected)) return context;
        // createConfigurationContext replaces, rather than layers, its override configuration.
        // Preserve the locale/window/font configuration supplied by AppLanguage.wrap().
        Configuration override = new Configuration(context.getResources().getConfiguration());
        int night = DARK.equals(selected) ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO;
        override.uiMode = (override.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | night;
        return context.createConfigurationContext(override);
    }

    public static boolean isDark(Context context) {
        return (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }
}
