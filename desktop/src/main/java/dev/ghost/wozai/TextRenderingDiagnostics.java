package dev.ghost.wozai;

import com.formdev.flatlaf.util.UIScale;
import java.awt.*;
import java.awt.font.FontRenderContext;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Locale;
import javax.swing.*;

/** Opt-in rendering evidence; records font/scaling/hints without user text or message contents. */
final class TextRenderingDiagnostics {
    static void write(Window window, Path file, float textScale) throws IOException {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Rendering diagnostics require the EDT");
        StringBuilder report = new StringBuilder();
        report.append("OS: ").append(System.getProperty("os.name")).append('\n');
        report.append("Java: ").append(System.getProperty("java.runtime.version")).append('\n');
        report.append("System transform: ").append(window.getGraphicsConfiguration().getDefaultTransform()).append('\n');
        report.append("FlatLaf user scale: ").append(UIScale.getUserScaleFactor()).append('\n');
        report.append("Application text scale: ").append(textScale).append('\n');
        report.append("Java2D scale override: ").append(System.getProperty("sun.java2d.uiScale", "unset")).append('\n');
        report.append("Desktop font hints: ").append(Toolkit.getDefaultToolkit().getDesktopProperty("awt.font.desktophints")).append('\n');
        report.append("Swing text AA: ").append(UIManager.get(RenderingHints.KEY_TEXT_ANTIALIASING)).append('\n');
        report.append("Swing LCD contrast: ").append(UIManager.get(RenderingHints.KEY_TEXT_LCD_CONTRAST)).append('\n');
        report.append("Control metrics below reflect Swing's text AA; raw graphics hints precede UI delegate drawing.\n");
        append(window, report);
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.writeString(file, report, StandardCharsets.UTF_8);
    }
    private static void append(Component component, StringBuilder report) {
        Font font = component.getFont();
        if (component.isShowing() && font != null && (component instanceof JLabel || component instanceof JTextField || component instanceof JTextArea || component instanceof AbstractButton || component instanceof JComboBox<?>)) {
            FontRenderContext context = component.getFontMetrics(font).getFontRenderContext();
            report.append(component.getClass().getSimpleName()).append(": font=").append(font.getFontName(Locale.ENGLISH))
                    .append(", size=").append(font.getSize2D()).append(", metricsAA=").append(context.getAntiAliasingHint())
                    .append(", metricsFM=").append(context.getFractionalMetricsHint());
            Graphics graphics = component.getGraphics();
            if (graphics instanceof Graphics2D g) {
                try {
                    report.append(", transform=").append(g.getTransform()).append(", rawAA=").append(g.getRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING))
                            .append(", rawFM=").append(g.getRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS));
                } finally { graphics.dispose(); }
            } else if (graphics != null) graphics.dispose();
            report.append('\n');
        }
        if (component instanceof Container container) for (Component child : container.getComponents()) append(child, report);
    }
}
