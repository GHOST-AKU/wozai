package dev.ghost.wozai;

import javax.swing.*;
import java.awt.*;
import java.nio.file.*;
import java.io.IOException;
import dev.ghost.nearbyim.i18n.LanguageRegistry;
import dev.ghost.nearbyim.i18n.LocalizedIOException;
import dev.ghost.nearbyim.i18n.UiText;

public final class Main {
    public static void main(String[] args) {
        if (java.util.Arrays.asList(args).contains("--install-desktop")) {
            Strings strings = new Strings(LanguageRegistry.SYSTEM);
            try {
                String root = System.getProperty("wozai.installDir", "");
                if (DesktopIdentity.windows() || root.isBlank()) throw new IOException("Linux packaged launcher required");
                Path entry = DesktopPlatform.installDesktopEntry(Path.of(root), DataLocation.linuxDataHome(System::getProperty, System::getenv));
                System.out.println(strings.text("desktopEntryInstalled", entry.toString()));
            } catch (IOException | RuntimeException e) {
                System.err.println(strings.text("desktopEntryFailed")); System.exit(1);
            }
            return;
        }
        SwingUtilities.invokeLater(() -> {
            DesktopStore store = null;
            Path path = null;
            String language = LanguageRegistry.SYSTEM;
            try {
                DataLocation.Selection location = DataLocation.select();
                path = location.path();
                language = startupLanguage(path);
                AppTheme.install(false);
                try (DataLocation.Prepared prepared = location.prepare()) {
                    store = new DesktopStore(prepared.path());
                }
                language = store.language();
                var identity = DesktopIdentity.load(path.resolve("identity.properties"));
                DesktopWindow window = new DesktopWindow(store, identity, path);
                PerformanceProbe.install(window, args); window.setVisible(true);
                if (java.util.Arrays.asList(args).contains("--text-diagnostics")) {
                    Path report = path.resolve("text-rendering.txt");
                    SwingUtilities.invokeLater(() -> {
                        try { window.writeTextDiagnostics(report); }
                        catch (IOException e) { System.err.println("Unable to write text rendering diagnostics: " + e.getClass().getSimpleName()); }
                    });
                }
            } catch (Exception e) {
                if (store != null) try { store.close(); } catch (Exception ignored) { }
                Strings strings = new Strings(language);
                JTextArea message = new JTextArea(startupMessage(strings, path, e)); message.setEditable(false); message.setLineWrap(true); message.setWrapStyleWord(true); message.setColumns(48);
                message.applyComponentOrientation(strings.rtl() ? ComponentOrientation.RIGHT_TO_LEFT : ComponentOrientation.LEFT_TO_RIGHT);
                JOptionPane.showOptionDialog(null, message, strings.text("app"), JOptionPane.DEFAULT_OPTION,
                        JOptionPane.ERROR_MESSAGE, null, new String[]{strings.text("gotIt")}, null);
            }
        });
    }
    static Path dataPath() throws IOException {
        return DataLocation.select().path();
    }
    private static String startupLanguage(Path path) {
        Path settings = path.resolve("settings.properties");
        if (!Files.isRegularFile(settings, LinkOption.NOFOLLOW_LINKS)) return LanguageRegistry.SYSTEM;
        try { return LanguageRegistry.normalizeSelection(AtomicFiles.read(settings).getProperty("language", LanguageRegistry.SYSTEM)); }
        catch (IOException e) { return LanguageRegistry.SYSTEM; }
    }
    static String startupMessage(Strings strings, Path path, Throwable failure) {
        UiText reason = UiText.of("dataPreparationFailed", path == null ? strings.text("unknownDataPath") : path.toString());
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof LocalizedIOException localized) { reason = localized.text; break; }
        }
        return strings.text("startupDetails", strings.text(DesktopPlatform.key("startupError")),
                path == null ? strings.text("unknownDataPath") : path.toString(), strings.text(reason));
    }
}
