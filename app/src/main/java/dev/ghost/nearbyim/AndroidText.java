package dev.ghost.nearbyim;

import android.content.Context;
import android.content.res.Configuration;
import android.icu.text.DateFormat;
import android.icu.text.MessageFormat;
import dev.ghost.nearbyim.i18n.I18nResources;
import dev.ghost.nearbyim.i18n.UiText;
import java.util.Date;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;

/** Resolve semantic messages only when displaying them, including nested transport labels. */
public final class AndroidText {
    private static final Map<String, MessageFormat> FORMATS = new LinkedHashMap<String, MessageFormat>(256, .75f, true) {
        protected boolean removeEldestEntry(Map.Entry<String, MessageFormat> eldest) { return size() > 256; }
    };
    private AndroidText() {}
    public static String get(Context context, UiText text) {
        return text == null || text.key.isEmpty() ? "" : get(context, text.key, text.arguments);
    }
    public static String get(Context context, String key, Object... arguments) {
        Context localized = AppLanguage.wrap(context);
        int resource = I18nResources.id(key);
        if (resource == 0) return get(localized, "error");
        String pattern = localized.getString(resource);
        Object[] resolved = arguments.clone();
        for (int i = 0; i < resolved.length; i++)
            if (resolved[i] instanceof UiText) resolved[i] = get(localized, (UiText) resolved[i]);
        Locale locale = localized.getResources().getConfiguration().getLocales().get(0);
        String cacheKey = locale.toLanguageTag() + '\u0000' + android.icu.util.TimeZone.getDefault().getID() + '\u0000' + pattern;
        // ICU formatters are mutable; the bounded cache retains no Context and
        // serializes formatting across UI and background notification callers.
        synchronized (FORMATS) {
            MessageFormat format = FORMATS.get(cacheKey);
            if (format == null) { format = new MessageFormat(pattern, locale); FORMATS.put(cacheKey, format); }
            return format.format(resolved);
        }
    }
    /** Preserve the unpersisted default from v0.2 for existing identities exactly once. */
    public static String initialNickname(Context context, boolean existingIdentity) {
        if (!existingIdentity) return get(context, "defaultNickname");
        Configuration legacy = new Configuration(context.getResources().getConfiguration());
        legacy.setLocale(Locale.SIMPLIFIED_CHINESE);
        return context.createConfigurationContext(legacy).getString(I18nResources.id("defaultNickname"));
    }
    public static String date(Context context, long time, String skeleton) {
        return DateFormat.getInstanceForSkeleton(skeleton, AppLanguage.locale(context)).format(new Date(time));
    }
    public static String messageState(Context context, String code) {
        if (code == null || code.isEmpty()) return "";
        return get(context, ChatStore.PENDING.equals(code) ? "pending"
                : ChatStore.DELIVERED.equals(code) ? "delivered" : "unknown");
    }
}
