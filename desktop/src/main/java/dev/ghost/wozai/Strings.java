package dev.ghost.wozai;

import com.ibm.icu.text.MessageFormat;
import dev.ghost.nearbyim.i18n.LanguageRegistry;
import dev.ghost.nearbyim.i18n.UiText;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Supplier;

final class Strings {
    private static final ResourceBundle.Control UTF8 = new ResourceBundle.Control() {
        @Override public List<String> getFormats(String baseName) { return FORMAT_PROPERTIES; }
        @Override public List<Locale> getCandidateLocales(String baseName, Locale locale) { return List.of(locale); }
        @Override public Locale getFallbackLocale(String baseName, Locale locale) { return null; }
        @Override public ResourceBundle newBundle(String baseName, Locale locale, String format, ClassLoader loader, boolean reload) throws IOException {
            String resource = toResourceName(toBundleName(baseName, locale), "properties");
            try (InputStream input = loader.getResourceAsStream(resource)) {
                return input == null ? null : new PropertyResourceBundle(new InputStreamReader(input, StandardCharsets.UTF_8));
            }
        }
    };
    private final Supplier<Locale> systemLocale;
    private final ResourceBundle english = ResourceBundle.getBundle("dev.ghost.wozai.Strings", Locale.ROOT, UTF8);
    private ResourceBundle bundle;
    private Locale locale;
    private String selection;
    private boolean rtl;
    private boolean fallbackBundle;
    Strings(String language) { this(language, () -> Locale.getDefault(Locale.Category.DISPLAY)); }
    Strings(String language, Supplier<Locale> systemLocale) { this.systemLocale = systemLocale; language(language); }
    void language(String language) {
        selection = LanguageRegistry.normalizeSelection(language);
        resolveLanguage();
    }
    private void resolveLanguage() {
        Locale system = systemLocale.get();
        LanguageRegistry.Language language = LanguageRegistry.resolve(selection, system);
        locale = LanguageRegistry.locale(selection, system);
        rtl = language.rtl;
        try {
            bundle = ResourceBundle.getBundle("dev.ghost.wozai.Strings", Locale.forLanguageTag(language.tag), UTF8);
            fallbackBundle = false;
        } catch (MissingResourceException e) { bundle = english; fallbackBundle = true; }
    }
    boolean refreshSystemLanguage() {
        if (!LanguageRegistry.SYSTEM.equals(selection)) return false;
        Locale previous = locale;
        resolveLanguage();
        return !previous.equals(locale);
    }
    static String pattern(String key, ResourceBundle translated, ResourceBundle fallback) {
        if (translated.containsKey(key)) return translated.getString(key);
        if (fallback.containsKey(key)) return fallback.getString(key);
        return fallback.getString("error");
    }
    String text(String key, Object... args) {
        Object[] rendered = args.clone();
        for (int i = 0; i < rendered.length; i++) if (rendered[i] instanceof UiText nested) rendered[i] = text(nested);
        Locale formattingLocale = fallbackBundle || !bundle.containsKey(key) ? Locale.ENGLISH : locale;
        return new MessageFormat(pattern(key, bundle, english), formattingLocale).format(rendered);
    }
    String text(UiText text) { return text == null || text.key.isEmpty() ? "" : text(text.key, text.arguments); }
    Locale locale() { return locale; }
    String selection() { return selection; }
    boolean rtl() { return rtl; }
}
