package dev.ghost.wozai;

import javax.swing.*;
import java.awt.*;
import java.nio.file.*;
import java.util.Locale;

public final class Main {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            DesktopStore store = null;
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                Path path = dataPath(); store = new DesktopStore(path);
                var identity = DesktopIdentity.load(path.resolve("identity.properties"));
                new DesktopWindow(store, identity, path).setVisible(true);
            } catch (Exception e) {
                if (store != null) try { store.close(); } catch (Exception ignored) { }
                Strings strings = new Strings(Locale.getDefault().getLanguage().equals("zh") ? "zh" : "en");
                JTextArea message = new JTextArea(strings.text("startupError")); message.setEditable(false); message.setLineWrap(true); message.setWrapStyleWord(true); message.setColumns(40);
                JOptionPane.showMessageDialog(null, message, strings.text("app"), JOptionPane.ERROR_MESSAGE);
            }
        });
    }
    static Path dataPath() {
        String override = System.getProperty("wozai.dataDir");
        if (override != null && !override.isBlank()) return Path.of(override).toAbsolutePath();
        if (DesktopIdentity.windows()) return Path.of(System.getenv("LOCALAPPDATA"), "WoZai");
        return Path.of(System.getProperty("user.home"), ".local", "share", "wozai");
    }
}
