package dev.ghost.wozai;

import java.util.Locale;
import java.util.Map;

/** Selects UI locale without changing the JVM's formatting or protocol defaults. */
final class DesktopLocale {
    static Locale swingLocale(Locale locale) {
        if (!locale.getLanguage().equals("zh")) return locale;
        return dev.ghost.nearbyim.i18n.LanguageRegistry.resolve("system",locale).tag.equals("zh-Hant") ? Locale.TRADITIONAL_CHINESE : Locale.SIMPLIFIED_CHINESE;
    }
    static Locale systemDisplayLocale() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux")
                ? linuxDisplayLocale(System.getenv()) : Locale.getDefault(Locale.Category.DISPLAY);
    }
    static Locale linuxDisplayLocale(Map<String, String> environment) {
        for (String key : new String[]{"LC_ALL", "LC_MESSAGES", "LANG"}) {
            String raw = environment.get(key);
            if (raw != null && !raw.isBlank()) return parseDisplayLocale(raw);
        }
        return Locale.ENGLISH;
    }
    static Locale parseDisplayLocale(String raw) {
        if (raw == null || raw.isBlank() || raw.contains("@")) return Locale.ENGLISH;
        String tag = raw.strip().split("\\.", 2)[0].replace('_', '-');
        if (tag.equalsIgnoreCase("C") || tag.equalsIgnoreCase("POSIX")) return Locale.ENGLISH;
        try { return new Locale.Builder().setLanguageTag(tag).build(); }
        catch (java.util.IllformedLocaleException invalid) { return Locale.ENGLISH; }
    }
}
