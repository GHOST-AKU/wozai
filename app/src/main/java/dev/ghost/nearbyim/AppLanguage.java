package dev.ghost.nearbyim;

import android.app.LocaleManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.os.LocaleList;
import dev.ghost.nearbyim.i18n.LanguageRegistry;
import java.util.Locale;

/** Locale selection is shared by the activity, service and all background renderers. */
public final class AppLanguage {
    public static final String PREFERENCES = "ui", KEY = "language";
    private static final String OS_MIGRATED = "languageOsMigrated";
    private AppLanguage() {}

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }
    private static void migrate(Context context) {
        SharedPreferences preferences = preferences(context);
        String previous = preferences.getString(KEY, LanguageRegistry.SYSTEM);
        String normalized = LanguageRegistry.normalizeSelection(previous);
        if (Build.VERSION.SDK_INT >= 33 && !preferences.getBoolean(OS_MIGRATED, false)) {
            LocaleManager manager = context.getSystemService(LocaleManager.class);
            // Existing explicit preferences are migrated once. Later changes in Settings win.
            if (manager != null && manager.getApplicationLocales().isEmpty()
                    && !LanguageRegistry.SYSTEM.equals(normalized))
                manager.setApplicationLocales(LocaleList.forLanguageTags(normalized));
            preferences.edit().putBoolean(OS_MIGRATED, true).putString(KEY, normalized).apply();
        } else if (!normalized.equals(previous)) preferences.edit().putString(KEY, normalized).apply();
    }
    public static String selection(Context context) {
        migrate(context);
        if (Build.VERSION.SDK_INT >= 33) {
            LocaleManager manager = context.getSystemService(LocaleManager.class);
            if (manager != null) {
                LocaleList locales = manager.getApplicationLocales();
                return locales.isEmpty() ? LanguageRegistry.SYSTEM
                        : LanguageRegistry.normalizeSelection(locales.get(0).toLanguageTag());
            }
        }
        return LanguageRegistry.normalizeSelection(preferences(context).getString(KEY, LanguageRegistry.SYSTEM));
    }
    public static Locale systemLocale(Context context) {
        if (Build.VERSION.SDK_INT >= 33) {
            LocaleManager manager = context.getSystemService(LocaleManager.class);
            if (manager != null && !manager.getSystemLocales().isEmpty()) return manager.getSystemLocales().get(0);
        }
        LocaleList locales = Resources.getSystem().getConfiguration().getLocales();
        return locales.isEmpty() ? Locale.ENGLISH : locales.get(0);
    }
    public static Locale locale(Context context) {
        return LanguageRegistry.locale(selection(context), systemLocale(context));
    }
    public static Context wrap(Context context) {
        Locale locale = locale(context);
        Configuration current = context.getResources().getConfiguration();
        if (current.getLocales().size() == 1 && current.getLocales().get(0).equals(locale)
                && current.getLayoutDirection() == android.text.TextUtils.getLayoutDirectionFromLocale(locale)) return context;
        Configuration configuration = new Configuration(current);
        configuration.setLocales(new LocaleList(locale));
        configuration.setLayoutDirection(locale);
        return context.createConfigurationContext(configuration);
    }
    /** Android 13+ recreates the activity through the native application locale setting. */
    public static void select(Context context, String selected) {
        String normalized = LanguageRegistry.normalizeSelection(selected);
        preferences(context).edit().putString(KEY, normalized).apply();
        if (Build.VERSION.SDK_INT >= 33) {
            LocaleManager manager = context.getSystemService(LocaleManager.class);
            if (manager != null) manager.setApplicationLocales(LanguageRegistry.SYSTEM.equals(normalized)
                    ? LocaleList.getEmptyLocaleList() : LocaleList.forLanguageTags(normalized));
        }
    }
}
