package dev.ghost.wozai;

import com.formdev.flatlaf.util.UIScale;
import java.awt.Toolkit;
import java.awt.event.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.swing.*;

/** Opt-in benchmark marker; normal launches install no probe or extra thread. */
final class PerformanceProbe {
    static void install(JFrame window, String[] args) throws IOException {
        for (int i = 0; i < args.length; i++) if (args[i].equals("--performance-ready-file")) {
            if (i + 1 == args.length) throw new IOException("Missing performance marker path");
            install(window, Path.of(args[i + 1])); return;
        }
    }
    static void install(JFrame window, Path marker) throws IOException {
        Path ready = marker.toAbsolutePath().normalize();
        Files.createDirectories(ready.getParent());
        if (Files.exists(ready)) throw new IOException("Performance marker already exists");
        Path stop = ready.resolveSibling(ready.getFileName() + ".stop");
        if (Files.exists(stop)) throw new IOException("Performance stop marker already exists");
        WatchService watcher = FileSystems.getDefault().newWatchService();
        ready.getParent().register(watcher, StandardWatchEventKinds.ENTRY_CREATE);
        window.addWindowListener(new WindowAdapter() {
            public void windowOpened(WindowEvent event) {
                SwingUtilities.invokeLater(() -> {
                    // Complete a full root-pane paint, synchronize the toolkit,
                    // then take another EDT turn before announcing readiness.
                    window.getRootPane().paintImmediately(0, 0, window.getRootPane().getWidth(), window.getRootPane().getHeight());
                    Toolkit.getDefaultToolkit().sync();
                    SwingUtilities.invokeLater(() -> {
                        if (!window.isShowing() || !window.isEnabled()) return;
                        var transform = window.getGraphicsConfiguration().getDefaultTransform();
                        Map<String, String> values = new LinkedHashMap<>();
                        for (String key : new String[]{"os.name", "os.version", "os.arch", "java.version", "java.vendor"}) values.put(key, System.getProperty(key));
                        values.put("system_scale_x", Double.toString(transform.getScaleX()));
                        values.put("system_scale_y", Double.toString(transform.getScaleY()));
                        values.put("flatlaf_scale", Float.toString(UIScale.getUserScaleFactor()));
                        values.put("ui_scale_override", System.getProperty("sun.java2d.uiScale", "unset"));
                        values.put("font", UIManager.getFont("Label.font").getFamily());
                        values.put("readiness", "visible root pane painted, toolkit synchronized, EDT round trip completed");
                        try { Files.writeString(ready, json(values), StandardOpenOption.CREATE_NEW); }
                        catch (IOException failure) { System.err.println("Performance marker failed: " + failure.getClass().getSimpleName()); }
                    });
                });
            }
            public void windowClosed(WindowEvent event) { try { watcher.close(); } catch (IOException ignored) { } }
        });
        Thread closer = new Thread(() -> {
            try {
                for (;;) {
                    WatchKey key = watcher.take();
                    key.pollEvents();
                    if (Files.exists(stop)) {
                        SwingUtilities.invokeLater(() -> window.dispatchEvent(new WindowEvent(window, WindowEvent.WINDOW_CLOSING)));
                        return;
                    }
                    if (!key.reset()) return;
                }
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            catch (ClosedWatchServiceException ignored) { }
            finally { try { watcher.close(); } catch (IOException ignored) { } }
        }, "nearbyim-performance-close");
        closer.setDaemon(true); closer.start();
    }
    static String json(Map<String, String> values) {
        StringBuilder output = new StringBuilder("{");
        values.forEach((key, value) -> { if (output.length() > 1) output.append(','); output.append(quote(key)).append(':').append(quote(value)); });
        return output.append('}').toString();
    }
    private static String quote(String value) {
        StringBuilder output = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            if (c == '\\' || c == '"') output.append('\\').append(c);
            else if (c < 32) output.append(String.format("\\u%04x", (int)c));
            else output.append(c);
        }
        return output.append('"').toString();
    }
}
