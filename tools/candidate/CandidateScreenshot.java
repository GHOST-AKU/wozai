package dev.ghost.wozai;

import java.awt.*;
import java.nio.file.*;
import java.util.UUID;
import java.util.concurrent.FutureTask;
import javax.imageio.ImageIO;
import javax.swing.*;

/** Captures the shipping UI with isolated local sample history; never opens user data. */
public final class CandidateScreenshot {
    private static <T> T edt(java.util.concurrent.Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }
    private static JList<?> chats(Component node) {
        if (node instanceof JList<?> list && "Chats".equals(list.getAccessibleContext().getAccessibleName())) return list;
        if (node instanceof Container parent) for (Component child : parent.getComponents()) {
            JList<?> result = chats(child);
            if (result != null) return result;
        }
        return null;
    }
    public static void main(String[] args) throws Exception {
        Path profile = Files.createTempDirectory("nearbyim-screenshot-");
        DesktopWindow window = null;
        try (DesktopStore store = new DesktopStore(profile)) {
            store.setSetting("language", "en");
            String peer = UUID.randomUUID().toString();
            store.peer(new DesktopStore.Peer(peer, "Phone", "", ""));
            long now = System.currentTimeMillis();
            store.save(peer, new DesktopStore.Message(UUID.randomUUID().toString(), "Hello! Are you nearby?", now - 120000, false, "received"));
            store.save(peer, new DesktopStore.Message(UUID.randomUUID().toString(), "I'm here. Let's chat directly.", now - 60000, true, "read"));
            var identity = DesktopIdentity.load(profile.resolve("identity.properties"));
            window = edt(() -> {
                AppTheme.install(false);
                DesktopWindow result = new DesktopWindow(store, identity, profile);
                result.setBounds(20, 20, 960, 620);
                result.setVisible(true);
                return result;
            });
            DesktopWindow target = window;
            long deadline = System.nanoTime() + 8_000_000_000L;
            boolean selected = false;
            while (System.nanoTime() < deadline) {
                selected = edt(() -> {
                    JList<?> list = chats(target);
                    if (list == null || list.getModel().getSize() != 1) return false;
                    list.setSelectedIndex(0);
                    return true;
                });
                if (selected) break;
                Thread.sleep(100);
            }
            if (!selected) throw new IllegalStateException("Sample history did not load");
            Robot robot = new Robot();
            robot.waitForIdle();
            Thread.sleep(1000);
            Rectangle bounds = edt(target::getBounds);
            if (!GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds().contains(bounds))
                throw new IllegalStateException("Capture window extends beyond the display");
            ImageIO.write(robot.createScreenCapture(bounds), "png", Path.of(args[0]).toFile());
        } finally {
            if (window != null) { DesktopWindow target = window; edt(() -> { target.dispose(); return null; }); }
        }
        System.exit(0);
    }
}
