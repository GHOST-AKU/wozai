package dev.ghost.wozai;

import javax.swing.*;
import java.awt.*;
import java.nio.file.*;
import java.util.Locale;

public final class Main {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            DesktopStore store = null;
            Path path = null;
            try {
                DataLocation.Selection location = DataLocation.select();
                path = location.path();
                AppTheme.install(false);
                try (DataLocation.Prepared prepared = location.prepare()) {
                    store = new DesktopStore(prepared.path());
                }
                var identity = DesktopIdentity.load(path.resolve("identity.properties"));
                new DesktopWindow(store, identity, path).setVisible(true);
            } catch (Exception e) {
                if (store != null) try { store.close(); } catch (Exception ignored) { }
                Strings strings = new Strings(Locale.getDefault().getLanguage().equals("zh") ? "zh" : "en");
                String detail = "\n\n数据目录：" + (path == null ? "无法确定" : path) + "\n\n" + startupReason(e);
                JTextArea message = new JTextArea(strings.text("startupError") + detail); message.setEditable(false); message.setLineWrap(true); message.setWrapStyleWord(true); message.setColumns(48);
                JOptionPane.showMessageDialog(null, message, strings.text("app"), JOptionPane.ERROR_MESSAGE);
            }
        });
    }
    static Path dataPath() {
        return DataLocation.select().path();
    }
    private static String startupReason(Exception failure) {
        String reason = failure.getMessage();
        if (reason != null && (reason.contains("目录") || reason.contains("迁移") || reason.contains("仍在运行"))) return reason;
        if (reason != null && reason.contains("already running")) return "该数据目录正在被另一个“我在”进程使用，请先关闭它。";
        if (reason != null && (reason.contains("identity") || reason.contains("Identity") || reason.contains("Windows")))
            return "无法读取已有设备身份。请检查原有文件是否损坏，以及是否使用同一 Windows 用户。原有身份不会被替换。";
        return "无法读取或写入此数据目录。请检查文件和权限。" + (reason == null ? "" : "\n" + reason);
    }
}
