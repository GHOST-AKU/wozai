package dev.ghost.wozai;

import java.text.MessageFormat;
import java.util.*;

final class Strings {
    private ResourceBundle bundle;
    private Locale locale;
    Strings(String language) { language(language); }
    void language(String language) {
        locale = Locale.forLanguageTag(language.equals("en") ? "en" : "zh");
        bundle = ResourceBundle.getBundle("dev.ghost.wozai.Strings", locale, ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES));
    }
    String text(String key, Object... args) { return new MessageFormat(bundle.getString(key), locale).format(args); }
    Locale locale() { return locale; }
}
