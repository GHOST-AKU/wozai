package dev.ghost.nearbyim.i18n;

import java.util.Locale;

public final class LanguageRegistryTests {
    private static int assertions;
    public static void main(String[] args) {
        equal(LanguageRegistry.normalizeSelection(null), "system");
        equal(LanguageRegistry.normalizeSelection("SYSTEM"), "system");
        equal(LanguageRegistry.normalizeSelection("zh"), "zh-Hans");
        equal(LanguageRegistry.normalizeSelection("zh_CN"), "zh-Hans");
        equal(LanguageRegistry.normalizeSelection("en-GB"), "en");
        equal(LanguageRegistry.normalizeSelection("unsupported"), "system");
        equal(LanguageRegistry.resolve("system", Locale.SIMPLIFIED_CHINESE).tag, "zh-Hans");
        equal(LanguageRegistry.resolve("system", Locale.UK).tag, "en");
        equal(LanguageRegistry.resolve("zh-Hans", Locale.US).tag, "zh-Hans");
        equal(LanguageRegistry.resolve("system", Locale.forLanguageTag("de-DE")).tag, "en");
        // Regional formats survive a supported system language; explicit selection is canonical.
        equal(LanguageRegistry.locale("system", Locale.UK), Locale.UK);
        equal(LanguageRegistry.locale("en", Locale.UK), Locale.ENGLISH);
        equal(LanguageRegistry.locale("system", Locale.forLanguageTag("zh-CN")), Locale.forLanguageTag("zh-CN"));
        equal(LanguageRegistry.resolve("system", Locale.forLanguageTag("zh-Hant-TW")).tag, "en");
        equal(LanguageRegistry.resolve("system", Locale.forLanguageTag("zh-CN-u-nu-hanidec")).tag, "zh-Hans");
        equal(LanguageRegistry.locale("system", Locale.forLanguageTag("zh-CN-u-nu-hanidec")), Locale.forLanguageTag("zh-CN-u-nu-hanidec"));
        equal(LanguageRegistry.locale("system", Locale.forLanguageTag("en-Latn-GB")), Locale.forLanguageTag("en-Latn-GB"));
        Object[] values = {"{literal} <name>"};
        UiText text = UiText.of("requestBody", values);
        values[0] = "changed";
        equal(text.arguments[0], "{literal} <name>");
        equal(text, UiText.of("requestBody", "{literal} <name>"));
        equal(UiText.EMPTY.key, "");
        equal(new LocalizedIOException(text).text, text);
        equal(new LocalizedIllegalArgumentException(text).text, text);
        boolean immutable = false;
        try { LanguageRegistry.languages().clear(); } catch (UnsupportedOperationException expected) { immutable = true; }
        equal(immutable, true);
        System.out.println("Language registry: " + assertions + " checks passed");
    }
    private static void equal(Object actual, Object expected) {
        assertions++;
        if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }
}
