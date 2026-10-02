package dev.ghost.wozai;

import dev.ghost.nearbyim.core.*;
import dev.ghost.nearbyim.core.Frame;
import java.awt.*;
import java.awt.event.WindowEvent;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import javax.imageio.ImageIO;
import javax.swing.*;

/** Real native GUI actions, language/scale changes and wire-to-composer integration. */
public final class GuiTests {
    private static <T> T edt(Callable<T> task) throws Exception {
        FutureTask<T> result = new FutureTask<>(task); SwingUtilities.invokeAndWait(result); return result.get();
    }
    private static List<Component> components(Component root) {
        List<Component> result = new ArrayList<>(); result.add(root);
        if (root instanceof Container c) for (Component child : c.getComponents()) result.addAll(components(child));
        return result;
    }
    private static JButton button(Component root, String text) {
        return components(root).stream().filter(c -> c instanceof JButton b && b.getText().equals(text)).map(c -> (JButton) c).findFirst().orElseThrow();
    }
    private static JTextArea area(Component root, String label) {
        return components(root).stream().filter(c -> c instanceof JTextArea a && label.equals(a.getAccessibleContext().getAccessibleName())).map(c -> (JTextArea) c).findFirst().orElseThrow();
    }
    private static void await(Callable<Boolean> condition, String message) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < deadline) { if (edt(condition)) return; Thread.sleep(20); }
        throw new AssertionError(message);
    }
    private static StreamConnection socket(Socket s) { return new StreamConnection() {
        public InputStream input() throws IOException { return s.getInputStream(); }
        public OutputStream output() throws IOException { return s.getOutputStream(); }
        public String label() { return "Phone"; }
        public void close() throws IOException { s.close(); }
    }; }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("wozai-gui-test");
        DesktopWindow window = null; FramedSession remote = null;
        String id = UUID.randomUUID().toString();
        try (DesktopStore store = new DesktopStore(root)) {
            store.setSetting("language", "en"); store.peer(new DesktopStore.Peer(id, "Phone", "", "")); store.draft(id, "saved draft");
            DesktopIdentity.Identity identity = DesktopIdentity.load(root.resolve("identity.properties"));
            window = edt(() -> { AppTheme.install(false); DesktopWindow w = new DesktopWindow(store, identity, root); w.setVisible(true); return w; });
            DesktopWindow w = window;
            await(() -> components(w).stream().anyMatch(c -> c instanceof JList<?> l && "Chats".equals(l.getAccessibleContext().getAccessibleName()) && l.getModel().getSize() == 1), "History did not load");
            edt(() -> { components(w).stream().filter(c -> c instanceof JList<?> l && "Chats".equals(l.getAccessibleContext().getAccessibleName())).forEach(c -> ((JList<?>) c).setSelectedIndex(0)); return null; });
            await(() -> area(w, "Type a message").getText().equals("saved draft"), "UI draft did not load");
            JTextField search = edt(() -> components(w).stream().filter(c -> c instanceof JTextField f && "Search chats".equals(f.getAccessibleContext().getAccessibleName())).map(c -> (JTextField)c).findFirst().orElseThrow());
            edt(() -> { search.setText("Absent person"); return null; });
            await(() -> components(w).stream().anyMatch(c -> c instanceof JList<?> l && "Chats".equals(l.getAccessibleContext().getAccessibleName()) && l.getModel().getSize()==0), "Search failed to filter nickname");
            edt(() -> { search.setText("pho"); return null; });
            await(() -> components(w).stream().anyMatch(c -> c instanceof JList<?> l && "Chats".equals(l.getAccessibleContext().getAccessibleName()) && l.getModel().getSize()==1), "Search failed partial nickname");
            edt(() -> { search.setText(""); return null; });
            JComboBox<?> languages = edt(() -> components(w).stream().filter(c -> c instanceof JComboBox<?> b && "English".equals(b.getItemAt(1))).map(c -> (JComboBox<?>) c).findFirst().orElseThrow());
            edt(() -> { languages.setSelectedIndex(0); return null; });
            await(() -> w.getTitle().equals("我在") && area(w, "输入消息").getText().equals("saved draft"), "Language change lost draft");
            edt(() -> { languages.setSelectedIndex(1); return null; });
            float original = edt(() -> area(w, "Type a message").getFont().getSize2D());
            edt(() -> { components(w).stream().filter(c -> c instanceof JComboBox<?> b && "Standard".equals(b.getItemAt(0))).forEach(c -> ((JComboBox<?>) c).setSelectedIndex(2)); return null; });
            if (edt(() -> area(w, "Type a message").getFont().getSize2D()) <= original) throw new AssertionError("Text scaling did not affect composer");
            edt(() -> { ((JTabbedPane) components(w).stream().filter(c -> c instanceof JTabbedPane).findFirst().orElseThrow()).setSelectedIndex(1); button(w, "Start LAN reception").doClick(); return null; });
            await(() -> area(w, "My connection addresses").getText().contains(":"), "GUI reception did not start");
            String address = edt(() -> area(w, "My connection addresses").getText().split("\n")[0]);
            LocalEndpoint endpoint;
            try { endpoint = LocalEndpoint.parse(address); }
            catch (IllegalArgumentException e) { throw new AssertionError("Displayed address is not connectable: " + address, e); }
            CountDownLatch hello = new CountDownLatch(1), ready = new CountDownLatch(1);
            BlockingQueue<Frame> text = new LinkedBlockingQueue<>();
            remote = new FramedSession(socket(new Socket(endpoint.address, endpoint.port)), id, "Phone", DeviceIdentity.generate(), new FramedSession.Listener() {
                public void onHello(Frame frame) { hello.countDown(); }
                public void onReady() { ready.countDown(); }
                public void onText(Frame frame) { text.add(frame); }
                public void onAck(String id) { }
                public void onClosed(String reason) { }
            });
            remote.start();
            await(() -> Arrays.stream(Window.getWindows()).anyMatch(dialog -> dialog.isVisible() && components(dialog).stream().anyMatch(c -> c instanceof JButton b && b.getText().equals("Approve and remember"))), "Consent controls missing");
            edt(() -> { for (Window dialog : Window.getWindows()) if (dialog instanceof JDialog && dialog.isVisible()) button(dialog, "Approve and remember").doClick(); return null; });
            if (!hello.await(3, TimeUnit.SECONDS)) throw new AssertionError("No phone HELLO"); remote.approve();
            if (!ready.await(3, TimeUnit.SECONDS)) throw new AssertionError("GUI consent did not connect");
            await(() -> button(w, "Send").isEnabled(), "Ready chat composer disabled");
            if (!edt(() -> UIManager.getLookAndFeel() instanceof com.formdev.flatlaf.FlatLaf)) throw new AssertionError("Android visual theme not installed");
            edt(() -> { ((JTabbedPane) components(w).stream().filter(c -> c instanceof JTabbedPane).findFirst().orElseThrow()).setSelectedIndex(0); area(w, "Type a message").setText("Windows → Phone 🙂"); button(w, "Send").doClick(); return null; });
            Frame outgoing = text.poll(3, TimeUnit.SECONDS);
            if (outgoing == null || !outgoing.body.equals("Windows → Phone 🙂")) throw new AssertionError("Send control did not reach phone");
            remote.acknowledge(outgoing.id);
            remote.send(new Frame(Frame.TEXT, UUID.randomUUID().toString(), "Phone → Windows\nHello!", System.currentTimeMillis()));
            await(() -> components(w).stream().filter(c -> c instanceof MessagePane).map(c -> ((MessagePane)c).text()).findFirst().orElseThrow().contains("Delivered") && components(w).stream().filter(c -> c instanceof MessagePane).map(c -> ((MessagePane)c).text()).findFirst().orElseThrow().contains("Hello!"), "Messages or receipt not rendered");
            edt(() -> { components(w).stream().filter(c -> c instanceof JComboBox<?> b && "Standard".equals(b.getItemAt(0))).forEach(c -> ((JComboBox<?>)c).setSelectedIndex(0)); return null; });
            await(() -> components(w).stream().filter(c -> c instanceof MessagePane).map(c -> ((MessagePane)c).text()).findFirst().orElseThrow().contains("Delivered"), "Receipt lost after restoring text size");
            if (args.length > 0) {
                Path screenshot = Path.of(args[0]); Files.createDirectories(screenshot.toAbsolutePath().getParent());
                Rectangle bounds = edt(w::getBounds); ImageIO.write(new Robot().createScreenCapture(bounds), "png", screenshot.toFile());
            }
            JComboBox<?> themes = edt(() -> components(w).stream().filter(c -> c instanceof JComboBox<?> b && "Dark".equals(b.getItemAt(1))).map(c -> (JComboBox<?>)c).findFirst().orElseThrow());
            edt(() -> { themes.setSelectedIndex(1); return null; });
            await(() -> AppTheme.dark && AppTheme.background.equals(new Color(0x101619)), "Dark theme did not match Android palette");
            edt(() -> { area(w, "Type a message").setText("unsent draft"); languages.setSelectedIndex(0); return null; });
            await(() -> area(w, "输入消息").getText().equals("unsent draft") && button(w, "发送").isEnabled(), "Live language change interrupted session or draft");
            if (!edt(() -> AppTheme.dark)) throw new AssertionError("Translation reset theme");
            edt(() -> { w.dispatchEvent(new WindowEvent(w, WindowEvent.WINDOW_CLOSING)); return null; });
            await(() -> !w.isDisplayable(), "GUI exit did not close");
            try (DesktopStore reopened = new DesktopStore(root)) { if (!reopened.draft(id).equals("unsent draft")) throw new AssertionError("Exit lost draft"); if (!reopened.setting("theme","").equals("dark")) throw new AssertionError("Theme not persisted"); }
            System.out.println("GuiTests: consent, messaging, receipts, live translation, nickname search, Android light/dark theme, text scaling, draft and exit passed");
        } finally {
            if (remote != null) remote.close("test");
            DesktopWindow w = window; if (w != null) edt(() -> { if (w.isDisplayable()) w.dispatchEvent(new WindowEvent(w, WindowEvent.WINDOW_CLOSING)); return null; });
        }
    }
}
