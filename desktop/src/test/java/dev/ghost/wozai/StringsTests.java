package dev.ghost.wozai;

import com.ibm.icu.text.DateFormat;
import com.ibm.icu.text.MessageFormat;
import dev.ghost.nearbyim.i18n.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.net.URL;
import java.net.URLClassLoader;
import java.text.Format;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Exercises the real Windows catalog and formatter, independently of the host language. */
public final class StringsTests {
    private static int passed;
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); passed++; }
    public static void main(String[] args) throws Exception {
        Strings strings = new Strings(LanguageRegistry.SYSTEM, () -> Locale.FRANCE);
        check(strings.locale().getLanguage().equals("en"), "Unsupported system language must fall back to English");
        check(new Strings("unknown", () -> Locale.SIMPLIFIED_CHINESE).selection().equals(LanguageRegistry.SYSTEM), "Unknown saved selection did not follow the system");
        check(new Strings("zh", () -> Locale.ENGLISH).selection().equals("zh-Hans"), "Legacy Chinese preference was not normalized");
        check(new Strings("zh-CN", () -> Locale.ENGLISH).text("send").equals("发送"), "Chinese regional alias lost its bundle");
        check(new Strings("en", () -> Locale.CHINESE).text("send").equals("Send"), "Explicit English followed Chinese host");
        String nickname = "O'Brien {0} \\ 朋友 🙂";
        check(new Strings("en").text("myNickname", nickname).equals("I'm " + nickname), "English apostrophe or literal nickname was damaged");
        check(new Strings("en").text("requestDetails", nickname, "{1}'").contains(nickname), "Nickname was interpreted as a message pattern");
        check(new Strings("en").text("deviceCount", 0).equals("0 devices"), "Zero device plural failed");
        check(new Strings("en").text("deviceCount", 1).equals("1 device"), "Singular device plural failed");
        check(new Strings("en").text("deviceCount", 2).equals("2 devices"), "Multiple device plural failed");
        check(new Strings("zh-Hans").text("deviceCount", 2).equals("2 台设备"), "Chinese plural failed");
        check(new Strings("en").text(UiText.of("connectionStatus", UiText.of("lan"), UiText.of("ready"))).equals("LAN · Connected"), "Nested structured status was not localized");
        ResourceBundle english = ResourceBundle.getBundle("dev.ghost.wozai.Strings", Locale.ENGLISH);
        ResourceBundle missing = new ListResourceBundle() { protected Object[][] getContents() { return new Object[0][0]; } };
        check(Strings.pattern("send", missing, english).equals("Send"), "Missing translation did not fall back to English");
        check(new Strings("en").text("unregisteredKey").equals(english.getString("error")), "Unknown key crashed or leaked a raw key");
        AtomicReference<Locale> host = new AtomicReference<>(Locale.UK);
        Strings following = new Strings(LanguageRegistry.SYSTEM, host::get);
        check(following.locale().equals(Locale.UK), "System region was lost");
        host.set(Locale.SIMPLIFIED_CHINESE);
        check(following.refreshSystemLanguage() && following.text("send").equals("发送"), "Running system language was not refreshed");
        following.language("en"); host.set(Locale.FRANCE);
        check(!following.refreshSystemLanguage() && following.text("send").equals("Send"), "System refresh overrode an explicit choice");
        TimeZone previousZone = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            Date when = new Date(9 * 60 * 60 * 1000L);
            String ukTime = new Strings(LanguageRegistry.SYSTEM, () -> Locale.UK).text("messageTime", when);
            String usTime = new Strings(LanguageRegistry.SYSTEM, () -> Locale.US).text("messageTime", when);
            check(ukTime.equals("09:00") && usTime.contains("AM"), "Time did not respect the system region: " + ukTime + " / " + usTime);
        } finally { TimeZone.setDefault(previousZone); }
        validateCatalogPatterns();
        missingCatalogKey();
        savedPreferences();
        startupErrors();
        check(strings.text("aboutBodyWindows", LanguageRegistry.VERSION).contains(LanguageRegistry.VERSION), "About version was hardcoded");
        System.out.println("StringsTests: " + passed + " checks passed (all catalog patterns, ICU plurals, apostrophes, literal arguments, locale fallback, preferences and safe startup errors)");
    }
    private static void validateCatalogPatterns() throws Exception {
        Set<Object> keys = null;
        for (var language : LanguageRegistry.languages()) {
            String suffix = language.tag.replace('-', '_'); Properties catalog = new Properties();
            try (InputStream input = StringsTests.class.getResourceAsStream("Strings_" + suffix + ".properties")) {
                if (input == null) throw new AssertionError("Missing registered catalog " + language.tag);
                catalog.load(new InputStreamReader(input, StandardCharsets.UTF_8));
            }
            if (keys == null) keys = catalog.keySet(); else check(keys.equals(catalog.keySet()), "Registered catalogs have unequal keys");
            Locale locale = LanguageRegistry.locale(language.tag, Locale.UK);
            for (String key : catalog.stringPropertyNames()) {
                MessageFormat formatter = new MessageFormat(catalog.getProperty(key), locale);
                Format[] formats = formatter.getFormatsByArgumentIndex(); Object[] values = new Object[formats.length]; Arrays.fill(values, 2);
                for (int i = 0; i < formats.length; i++) if (formats[i] instanceof DateFormat) values[i] = new Date(0);
                check(formatter.format(values) != null, language.tag + ": invalid ICU pattern for " + key);
            }
        }
    }
    private static void savedPreferences() throws Exception {
        Path root = Files.createTempDirectory("nearbyim-language-preferences-");
        try (DesktopStore store = new DesktopStore(root)) {
            check(store.language().equals(LanguageRegistry.SYSTEM), "New profile did not follow the system");
            Properties old = new Properties(); old.setProperty("language", "zh"); old.setProperty("nickname", "O'Brien 朋友");
            AtomicFiles.write(root.resolve("settings.properties"), old);
            check(store.language().equals("zh-Hans") && store.setting("language", "").equals("zh-Hans"), "Old preference was not persistently migrated");
            check(store.setting("nickname", "").equals("O'Brien 朋友"), "Language migration changed the nickname");
            store.setSetting("language", "en"); check(store.language().equals("en"), "Legacy English preference was lost");
            store.setSetting("language", "system"); check(store.language().equals("system"), "System preference could not be persisted");
        } finally { deleteTree(root); }
    }
    private static void missingCatalogKey() throws Exception {
        Path root = Files.createTempDirectory("nearbyim-missing-translation-"); Path resources = root.resolve("dev/ghost/wozai"); Files.createDirectories(resources);
        try {
            for (String file : List.of("Strings.properties", "Strings_en.properties")) {
                try (InputStream input = StringsTests.class.getResourceAsStream(file)) { Files.copy(input, resources.resolve(file)); }
            }
            Files.writeString(resources.resolve("Strings_zh_Hans.properties"), "send=发送\n", StandardCharsets.UTF_8);
            URL[] locations = {root.toUri().toURL(), Strings.class.getProtectionDomain().getCodeSource().getLocation(),
                    LanguageRegistry.class.getProtectionDomain().getCodeSource().getLocation(), MessageFormat.class.getProtectionDomain().getCodeSource().getLocation()};
            try (URLClassLoader isolated = new URLClassLoader(locations, ClassLoader.getPlatformClassLoader())) {
                Class<?> type = isolated.loadClass(Strings.class.getName());
                var constructor = type.getDeclaredConstructor(String.class); constructor.setAccessible(true); Object strings = constructor.newInstance("zh-Hans");
                var text = type.getDeclaredMethod("text", String.class, Object[].class); text.setAccessible(true);
                check(text.invoke(strings, "send", new Object[0]).equals("发送"), "Isolated catalog did not load its translated key");
                check(text.invoke(strings, "deviceCount", new Object[]{1}).equals("1 device"), "Missing Chinese plural key was not formatted with English fallback rules");
            }
            try (URLClassLoader isolated = new URLClassLoader(locations, ClassLoader.getPlatformClassLoader()) {
                @Override public InputStream getResourceAsStream(String name) {
                    return name.endsWith("Strings_en.properties") || name.endsWith("Strings_zh_Hans.properties") ? null : super.getResourceAsStream(name);
                }
            }) {
                Class<?> type = isolated.loadClass(Strings.class.getName());
                var constructor = type.getDeclaredConstructor(String.class); constructor.setAccessible(true); Object strings = constructor.newInstance("zh-Hans");
                var text = type.getDeclaredMethod("text", String.class, Object[].class); text.setAccessible(true);
                check(text.invoke(strings, "deviceCount", new Object[]{1}).equals("1 device"), "Missing locale bundles did not fall back to the English base catalog");
            }
        } finally { deleteTree(root); }
    }
    private static void startupErrors() throws Exception {
        Strings english = new Strings("en"); Path path = Path.of("O'Brien {0} folder");
        String diagnostic = "raw exception 目录 secret detail";
        String message = Main.startupMessage(english, path, new IOException(diagnostic));
        check(message.contains(path.toString()) && !message.contains(diagnostic), "Startup exposed an untranslated diagnostic reason");
        message = Main.startupMessage(english, path, new IOException(diagnostic, new LocalizedIOException(UiText.of("dataInUse", path.toString()))));
        check(message.contains("Another NearbyIM") && !message.contains(diagnostic), "Structured startup cause was ignored");
        try { DataLocation.select(Map.of("wozai.dataDir", "\u0000")::get, key -> null, false); throw new AssertionError("Invalid startup path was accepted"); }
        catch (LocalizedIOException expected) { check(expected.text.key.equals("dataPreparationFailed"), "Invalid startup path did not return structured text"); }
        Path root = Files.createTempDirectory("nearbyim-broken-identity-"); Path identity = root.resolve("identity.properties");
        try {
            Files.writeString(identity, "broken identity");
            try { DesktopIdentity.load(identity); throw new AssertionError("Corrupt identity was replaced"); }
            catch (LocalizedIOException expected) {
                check(expected.text.key.equals("identityLoadFailed"), "Identity failure was not structured");
                check(Files.readString(identity).equals("broken identity"), "Identity failure overwrote data");
            }
        } finally { deleteTree(root); }
    }
    private static void deleteTree(Path root) throws IOException {
        try (var files = Files.walk(root)) { for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file); }
    }
}
