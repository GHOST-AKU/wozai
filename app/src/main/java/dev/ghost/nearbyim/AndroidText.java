package dev.ghost.nearbyim;

import android.content.Context;
import android.content.res.Configuration;
import android.icu.text.DateFormat;
import android.icu.text.MessageFormat;
import dev.ghost.nearbyim.i18n.I18nResources;
import dev.ghost.nearbyim.i18n.UiText;
import java.util.Date;
import java.util.Locale;

/** Resolve semantic messages only when displaying them, including nested transport labels. */
public final class AndroidText {
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
        return new MessageFormat(pattern, AppLanguage.locale(context)).format(resolved);
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
