package dev.ghost.wozai.next;

import com.ibm.icu.text.MessageFormat;
import dev.ghost.nearbyim.i18n.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Reads the existing generated desktop catalogs without changing their source. */
final class BridgeCatalog {
    private String selection = LanguageRegistry.SYSTEM;
    private Locale locale = Locale.ENGLISH;
    private Map<String, String> strings = Map.of();

    BridgeCatalog(String selection) throws IOException { language(selection); }

    synchronized void language(String value) throws IOException {
        selection = LanguageRegistry.normalizeSelection(value);
        LanguageRegistry.Language language = LanguageRegistry.resolve(selection, Locale.getDefault());
        locale = LanguageRegistry.locale(selection, Locale.getDefault());
        Map<String, String> result = new TreeMap<>();
        read("Strings.properties", result);
        read("Strings_" + language.tag.replace('-', '_') + ".properties", result);
        strings = Collections.unmodifiableMap(result);
    }

    private static void read(String name, Map<String, String> values) throws IOException {
        try (InputStream stream = BridgeCatalog.class.getResourceAsStream("/dev/ghost/wozai/" + name)) {
            if (stream == null) throw new IOException("Missing localized catalog");
            Properties properties = new Properties();
            properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
            for (String key : properties.stringPropertyNames()) values.put(key, properties.getProperty(key));
        }
    }

    synchronized String text(UiText text) {
        if (text == null || text.key.isEmpty()) return "";
        String pattern = strings.getOrDefault(text.key, strings.getOrDefault("error", "Unable to complete this action."));
        Object[] arguments = text.arguments.clone();
        for (int i = 0; i < arguments.length; i++) if (arguments[i] instanceof UiText nested) arguments[i] = text(nested);
        try { return new MessageFormat(pattern, locale).format(arguments); }
        catch (RuntimeException invalid) { return strings.getOrDefault("error", "Unable to complete this action."); }
    }

    synchronized String error(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof LocalizedIOException localized) return text(localized.text);
        }
        return text(UiText.of("error"));
    }

    synchronized Map<String, String> strings() { return strings; }
    synchronized String effectiveLanguage() { return LanguageRegistry.resolve(selection, Locale.getDefault()).tag; }
    synchronized boolean rtl() { return LanguageRegistry.resolve(selection, Locale.getDefault()).rtl; }
}
