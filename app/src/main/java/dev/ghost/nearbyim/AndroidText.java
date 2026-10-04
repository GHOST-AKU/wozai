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
    private static final class CachedFormat {
        final MessageFormat value; final String zone;
        CachedFormat(MessageFormat value, String zone) { this.value = value; this.zone = zone; }
    }
    private static final Map<String, CachedFormat> FORMATS = new LinkedHashMap<String, CachedFormat>(256, .75f, true) {
        protected boolean removeEldestEntry(Map.Entry<String, CachedFormat> eldest) { return size() > 256; }
    };
    private static boolean temporal(String pattern) {
        android.icu.text.MessagePattern parsed = new android.icu.text.MessagePattern(pattern);
        for (int i = 0; i < parsed.countParts(); ++i) {
            android.icu.text.MessagePattern.Part part = parsed.getPart(i);
            if (part.getType() == android.icu.text.MessagePattern.Part.Type.ARG_TYPE) {
                String type = parsed.getSubstring(part); if (type.equals("date") || type.equals("time")) return true;
            }
        }
        return false;
    }
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
        String cacheKey = locale.toLanguageTag() + '\u0000' + pattern;
        // ICU formatters are mutable; the bounded cache retains no Context and
        // serializes formatting across UI and background notification callers.
        synchronized (FORMATS) {
            CachedFormat cached = FORMATS.get(cacheKey);
            if (cached == null || cached.zone != null && !cached.zone.equals(android.icu.util.TimeZone.getDefault().getID())) {
                String zone = temporal(pattern) ? android.icu.util.TimeZone.getDefault().getID() : null;
                cached = new CachedFormat(new MessageFormat(pattern, locale), zone); FORMATS.put(cacheKey, cached);
            }
            return cached.value.format(resolved);
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
