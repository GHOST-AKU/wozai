package dev.ghost.wozai;

import dev.ghost.nearbyim.i18n.UiText;
import dev.ghost.nearbyim.i18n.LanguageRegistry;

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
        return components(root).stream().filter(c -> c instanceof JButton b && (b.getText().equals(text)||text.equals(b.getAccessibleContext().getAccessibleName()))).map(c -> (JButton) c).findFirst().orElseThrow();
    }
    private static void language(JComboBox<?> choices, String tag) {
        for (int i = 0; i < choices.getItemCount(); i++) if (choices.getItemAt(i) instanceof DesktopWindow.LanguageOption option && option.tag().equals(tag)) {
            choices.setSelectedIndex(i); return;
        }
        throw new AssertionError("Missing language option " + tag);
    }
    private static JDialog dialog(DesktopWindow window, String title) {
        return Arrays.stream(window.getOwnedWindows()).filter(w -> w instanceof JDialog d && d.isVisible() && d.getTitle().equals(title)).map(w -> (JDialog) w).findFirst().orElseThrow();
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
        DesktopWindow window = null; FramedSession remote = null;AttachmentTransfer phoneTransfer=null;
        String id = UUID.randomUUID().toString();
        Locale originalDisplayLocale = Locale.getDefault(Locale.Category.DISPLAY);
        try (DesktopStore store = new DesktopStore(root)) {
            store.setSetting("language", "en"); store.peer(new DesktopStore.Peer(id, "Phone", "", "")); store.draft(id, "saved draft");
            for (int i = 0; i < 35; i++) store.save(id, new DesktopStore.Message(UUID.randomUUID().toString(), "Earlier chat message " + i, 1_700_000_000_000L + i * 60_000L, false, "received"));
            DesktopIdentity.Identity identity = DesktopIdentity.load(root.resolve("identity.properties"));
            window = edt(() -> { AppTheme.install(false); DesktopWindow w = new DesktopWindow(store, identity, root); w.setVisible(true); return w; });
            DesktopWindow w = window;
            if (!w.getIconImages().stream().map(image -> image.getWidth(null)).toList().equals(DesktopIdentity.windows()
                    ? List.of(16, 24, 32, 48, 64, 128, 256) : List.of(16, 22, 24, 32, 48, 64, 96, 128, 256, 512)))
                throw new AssertionError("Window did not install formal platform icon sizes");
            await(() -> components(w).stream().anyMatch(c -> c instanceof JList<?> l && "Chats".equals(l.getAccessibleContext().getAccessibleName()) && l.getModel().getSize() == 1), "History did not load");
            edt(() -> { components(w).stream().filter(c -> c instanceof JList<?> l && "Chats".equals(l.getAccessibleContext().getAccessibleName())).forEach(c -> ((JList<?>) c).setSelectedIndex(0)); return null; });
            await(() -> area(w, "Type a message").getText().equals("saved draft"), "UI draft did not load");
            JTextField search = edt(() -> components(w).stream().filter(c -> c instanceof JTextField f && "Search chats".equals(f.getAccessibleContext().getAccessibleName())).map(c -> (JTextField)c).findFirst().orElseThrow());
            edt(() -> { search.setText("Absent person"); return null; });
            await(() -> components(w).stream().anyMatch(c -> c instanceof JList<?> l && "Chats".equals(l.getAccessibleContext().getAccessibleName()) && l.getModel().getSize()==0), "Search failed to filter nickname");
            edt(() -> { search.setText("pho"); return null; });
            await(() -> components(w).stream().anyMatch(c -> c instanceof JList<?> l && "Chats".equals(l.getAccessibleContext().getAccessibleName()) && l.getModel().getSize()==1), "Search failed partial nickname");
            edt(() -> { search.setText(""); return null; });
            JComboBox<?> languages = edt(() -> components(w).stream().filter(c -> c instanceof JComboBox<?> b && "Interface language".equals(b.getAccessibleContext().getAccessibleName())).map(c -> (JComboBox<?>) c).findFirst().orElseThrow());
            edt(() -> { language(languages, "zh-Hans"); return null; });
            await(() -> w.getTitle().equals("我在") && area(w, "输入消息").getText().equals("saved draft"), "Language change lost draft");
            edt(() -> { language(languages, "en"); return null; });
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
            java.util.concurrent.atomic.AtomicReference<AttachmentTransfer> transferRef=new java.util.concurrent.atomic.AtomicReference<>();
            remote = new FramedSession(socket(new Socket(endpoint.address, endpoint.port)), id, "Phone", DeviceIdentity.generate(), new FramedSession.Listener() {
                public void onHello(Frame frame) { hello.countDown(); }
                public void onReady() { ready.countDown(); }
                public void onText(Frame frame) { text.add(frame); }
                public void onAttachment(Frame frame){transferRef.get().receive(frame,true);}
                public void onAck(String id) { }
                public void onClosed(UiText reason) { }
            });
            FramedSession phone=remote;
            remote.start();
            await(() -> Arrays.stream(Window.getWindows()).anyMatch(dialog -> dialog.isVisible() && components(dialog).stream().anyMatch(c -> c instanceof JButton b && b.getText().equals("Approve and remember"))), "Consent controls missing");
            JDialog consent = edt(() -> dialog(w, "Chat request"));
            edt(() -> { language(languages, "zh-Hans"); return null; });
            await(() -> consent.isVisible() && consent.getTitle().equals("聊天请求") && button(consent, "同意并记住").isEnabled(), "Language change lost pending consent");
            edt(() -> { language(languages, "en"); button(consent, "Approve and remember").doClick(); return null; });
            if (!hello.await(3, TimeUnit.SECONDS)) throw new AssertionError("No phone HELLO"); remote.approve();
            if (!ready.await(3, TimeUnit.SECONDS)) throw new AssertionError("GUI consent did not connect");
            phoneTransfer=new AttachmentTransfer(root.resolve("phone-attachments"),new AttachmentTransfer.Wire(){public boolean send(Frame frame){return phone.sendAttachment(frame);}public void abort(){phone.close(UiText.EMPTY);}},record->{},phone.attachmentChunkSize(),phone.attachmentSizeLimit(),true);transferRef.set(phoneTransfer);
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
            edt(()->{
                JButton sendIcon=button(w,"Send");if(sendIcon.getIcon()==null||!sendIcon.getText().isEmpty())throw new AssertionError("Send icon not applied");
                button(w,"Emoji").doClick();JPopupMenu emojis=(JPopupMenu)Arrays.stream(MenuSelectionManager.defaultManager().getSelectedPath()).filter(c->c instanceof JPopupMenu).findFirst().orElseThrow();button(emojis,"🙂").doClick();if(!area(w,"Type a message").getText().contains("🙂"))throw new AssertionError("Emoji did not enter composer");area(w,"Type a message").setText("");
                button(w,"Attachments").doClick();JPopupMenu choices=(JPopupMenu)Arrays.stream(MenuSelectionManager.defaultManager().getSelectedPath()).filter(c->c instanceof JPopupMenu).findFirst().orElseThrow();if(button(choices,"Photo").getIcon()==null||button(choices,"File").getIcon()==null)throw new AssertionError("Attachment icons missing");MenuSelectionManager.defaultManager().clearSelectedPath();return null;
            });
            Path photo=root.resolve("chat-photo.png");java.awt.image.BufferedImage pixels=new java.awt.image.BufferedImage(800,600,java.awt.image.BufferedImage.TYPE_INT_RGB);Graphics2D paint=pixels.createGraphics();paint.setPaint(new GradientPaint(0,0,new Color(80,185,210),0,600,new Color(240,224,159)));paint.fillRect(0,0,800,600);paint.setColor(new Color(25,100,80));paint.fillOval(-120,340,800,550);paint.dispose();ImageIO.write(pixels,"png",photo.toFile());
            JScrollPane photoScroll=edt(()->(JScrollPane)SwingUtilities.getAncestorOfClass(JScrollPane.class,components(w).stream().filter(c->c instanceof MessagePane).findFirst().orElseThrow()));
            edt(()->{photoScroll.getVerticalScrollBar().setValue(photoScroll.getVerticalScrollBar().getMaximum());return null;});
            String photoId=phoneTransfer.offer(()->Files.newInputStream(photo),"chat-photo.png","image/png").get(5,TimeUnit.SECONDS);
            await(()->components(w).stream().anyMatch(c->c instanceof JLabel l&&l.getIcon() instanceof ImageIcon&&l.getAccessibleContext().getAccessibleName()!=null&&l.getAccessibleContext().getAccessibleName().contains("chat-photo.png")),"Automatic photo reception did not produce a bubble");
            await(()->{JScrollBar bar=photoScroll.getVerticalScrollBar();return bar.getValue()+bar.getVisibleAmount()>=bar.getMaximum()-24;},"Thumbnail growth moved chat away from newest message");
            edt(()->{JLabel preview=components(w).stream().filter(c->c instanceof JLabel l&&l.getIcon() instanceof ImageIcon&&l.getAccessibleContext().getAccessibleName()!=null&&l.getAccessibleContext().getAccessibleName().contains("chat-photo.png")).map(c->(JLabel)c).findFirst().orElseThrow();preview.dispatchEvent(new java.awt.event.MouseEvent(preview,java.awt.event.MouseEvent.MOUSE_CLICKED,1,0,10,10,1,false,java.awt.event.MouseEvent.BUTTON1));return null;});
            JDialog viewer=edt(()->dialog(w,"chat-photo.png"));
            await(()->components(viewer).stream().anyMatch(c->c instanceof JLabel l&&l.getText().contains("Mouse wheel")),"Internal viewer did not decode photo");
            edt(()->{button(viewer,"Zoom in").doClick();PhotoViewer.Canvas canvas=components(viewer).stream().filter(c->c instanceof PhotoViewer.Canvas).map(c->(PhotoViewer.Canvas)c).findFirst().orElseThrow();if(canvas.zoom()<=1)throw new AssertionError("Viewer button did not zoom");if(button(viewer,"Save as").getIcon()==null)throw new AssertionError("Viewer save missing");return null;});
            if(args.length>0){Rectangle bounds=edt(viewer::getBounds);ImageIO.write(new Robot().createScreenCapture(bounds),"png",Path.of(args[0].replace(".png","-photo.png")).toFile());}
            edt(()->{button(viewer,"Close").doClick();return null;});
            if(!Arrays.equals(Files.readAllBytes(photo),Files.readAllBytes(store.attachmentFile(id,store.messages(id).stream().filter(m->m.id().equals(photoId)).findFirst().orElseThrow().attachment().info))))throw new AssertionError("Photo preview changed transferred bytes");
            Path document=root.resolve("report.pdf");Files.write(document,new byte[24576]);String documentId=phoneTransfer.offer(()->Files.newInputStream(document),"report.pdf","application/pdf").get(5,TimeUnit.SECONDS);
            await(()->components(w).stream().anyMatch(c->c instanceof JTextArea a&&a.getText().contains("report.pdf")&&a.getText().contains("24.0 KiB")&&a.getText().contains("Received")),"Compact file card not rendered");
            edt(()->{photoScroll.getVerticalScrollBar().setValue(photoScroll.getVerticalScrollBar().getMaximum());area(w,"Type a message").setText("收到文件和照片 🙂");return null;});
            new Robot().waitForIdle();
            if (args.length > 0) {
                Path screenshot = Path.of(args[0]); Files.createDirectories(screenshot.toAbsolutePath().getParent());
                Rectangle bounds = edt(w::getBounds); ImageIO.write(new Robot().createScreenCapture(bounds), "png", screenshot.toFile());
            }
            JComboBox<?> themes = edt(() -> components(w).stream().filter(c -> c instanceof JComboBox<?> b && "Dark".equals(b.getItemAt(1))).map(c -> (JComboBox<?>)c).findFirst().orElseThrow());
            edt(() -> { themes.setSelectedIndex(1); return null; });
            await(() -> AppTheme.dark && AppTheme.background.equals(new Color(0x101619)) && area(w,"Type a message").getForeground().equals(AppTheme.ink) && area(w,"Type a message").getBackground().equals(AppTheme.surface), "Dark theme composer contrast did not match Android palette");
            if(args.length>0) { Rectangle bounds=edt(w::getBounds); ImageIO.write(new Robot().createScreenCapture(bounds),"png",Path.of(args[0].replace(".png","-dark.png")).toFile()); }
            edt(() -> { area(w, "Type a message").setText("unsent draft"); search.setText("pho"); button(w, "How to use").doClick(); button(w, "About NearbyIM").doClick(); button(w, "Trusted devices").doClick(); return null; });
            JDialog help = edt(() -> dialog(w, "How to use")), about = edt(() -> dialog(w, "About NearbyIM")), trust = edt(() -> dialog(w, "Trusted devices"));
            MessagePane messages = edt(() -> components(w).stream().filter(c -> c instanceof MessagePane).map(c -> (MessagePane)c).findFirst().orElseThrow());
            JScrollPane chatScroll = edt(() -> (JScrollPane)SwingUtilities.getAncestorOfClass(JScrollPane.class, messages));
            edt(() -> { chatScroll.getVerticalScrollBar().setValue(180); return null; });
            int previousScroll = edt(() -> chatScroll.getVerticalScrollBar().getValue());
            edt(() -> { language(languages, "zh-Hans"); return null; });
            await(() -> area(w, "输入消息").getText().equals("unsent draft") && button(w, "发送").isEnabled() && messages.text().contains("已送达"), "Live language change interrupted session or draft");
            await(() -> help.getTitle().equals("使用说明") && about.getTitle().equals("关于我在") && trust.getTitle().equals("已信任设备"), "Open dialogs kept the old language");
            if (!edt(() -> components(help).stream().anyMatch(c -> c instanceof JTextArea a && a.getText().contains("局域网")) && components(about).stream().anyMatch(c -> c instanceof JTextArea a && a.getText().contains(LanguageRegistry.VERSION)))) throw new AssertionError("Open information bodies/version did not refresh");
            if (!edt(() -> search.getText().equals("pho") && components(w).stream().anyMatch(c -> c instanceof JList<?> list && "聊天".equals(list.getAccessibleContext().getAccessibleName()) && list.getSelectedValue() instanceof DesktopStore.Peer peer && peer.id().equals(id)))) throw new AssertionError("Language change lost search or selected history");
            if (previousScroll == 0 || Math.abs(edt(() -> chatScroll.getVerticalScrollBar().getValue()) - previousScroll) > 32) throw new AssertionError("Language change lost the conversation scroll position: "+previousScroll+" -> "+edt(() -> chatScroll.getVerticalScrollBar().getValue()));
            for (String tag : new String[]{"zh-Hant", "ja", "ko"}) {
                Strings translated = new Strings(tag);
                edt(() -> { language(languages, tag); return null; });
                await(() -> area(w, translated.text("composer")).getText().equals("unsent draft")
                        && button(w, translated.text("send")).isEnabled()
                        && messages.text().contains(translated.text("delivered"))
                        && help.getTitle().equals(translated.text("help"))
                        && about.getTitle().equals(translated.text("about"))
                        && trust.getTitle().equals(translated.text("trustedDevices")), "New locale lost live chat or open dialogs: " + tag);
                if (!remote.isReady()) throw new AssertionError("New locale closed the live session: " + tag);
                if (args.length > 0) {
                    Rectangle bounds = edt(w::getBounds);
                    ImageIO.write(new Robot().createScreenCapture(bounds), "png", Path.of(args[0].replace(".png", "-" + tag + ".png")).toFile());
                }
            }
            edt(() -> { Locale.setDefault(Locale.Category.DISPLAY, Locale.US); language(languages, LanguageRegistry.SYSTEM); return null; });
            await(() -> area(w, "Type a message").getText().equals("unsent draft") && help.getTitle().equals("How to use"), "System choice did not resolve English");
            edt(() -> { Locale.setDefault(Locale.Category.DISPLAY, Locale.SIMPLIFIED_CHINESE); return null; });
            await(() -> help.getTitle().equals("使用说明") && trust.getTitle().equals("已信任设备") && area(w, "输入消息").getText().equals("unsent draft"), "Running system language change did not refresh open UI");
            if (!store.language().equals(LanguageRegistry.SYSTEM)) throw new AssertionError("System refresh replaced the saved system preference");
            if (!remote.isReady() || !remote.send(new Frame(Frame.TEXT, UUID.randomUUID().toString(), "Still connected after language changes", System.currentTimeMillis()))) throw new AssertionError("Language changes closed the live wire session");
            await(() -> messages.text().contains("Still connected after language changes"), "Live connection did not receive after language changes");
            if (!edt(() -> AppTheme.dark)) throw new AssertionError("Translation reset theme");
            edt(() -> { w.dispatchEvent(new WindowEvent(w, WindowEvent.WINDOW_CLOSING)); return null; });
            await(() -> !w.isDisplayable(), "GUI exit did not close");
            try (DesktopStore reopened = new DesktopStore(root)) { if (!reopened.draft(id).equals("unsent draft")) throw new AssertionError("Exit lost draft"); if (!reopened.setting("theme","").equals("dark")) throw new AssertionError("Theme not persisted"); }
            startupUsesSavedLanguage(root);
            bluetoothScanCancellation(root.resolve("scan-cancellation"));
            System.out.println("GuiTests: consent, messaging, receipts, live and system translation, open dialogs, search/selection/scroll preservation, nickname search, light/dark theme, text scaling, startup preference, draft and exit passed");
        } finally {
            Locale.setDefault(Locale.Category.DISPLAY, originalDisplayLocale);
            if(phoneTransfer!=null)phoneTransfer.close();
            if (remote != null) remote.close(UiText.EMPTY);
            DesktopWindow w = window; if (w != null) edt(() -> { if (w.isDisplayable()) w.dispatchEvent(new WindowEvent(w, WindowEvent.WINDOW_CLOSING)); return null; });
        }
    }
    private static final class TestInquiry implements DesktopBluetooth.Inquiry {
        final CountDownLatch started = new CountDownLatch(1), closed = new CountDownLatch(1), finish = new CountDownLatch(1);
        final boolean delayed;
        TestInquiry(boolean delayed) { this.delayed = delayed; }
        public List<DesktopBluetooth.Device> scan(int seconds) throws IOException {
            started.countDown();
            try { if (!finish.await(8, TimeUnit.SECONDS)) throw new IOException("Test inquiry timed out"); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
            return List.of(new DesktopBluetooth.Device("AB:CD:01:23:45:67", delayed ? "Stale scan" : "Current scan", false));
        }
        public void close() { closed.countDown(); if (!delayed) finish.countDown(); }
    }
    private static void bluetoothScanCancellation(Path root) throws Exception {
        List<TestInquiry> inquiries = new ArrayList<>();
        DesktopWindow window = null;
        try (DesktopStore store = new DesktopStore(root)) {
            store.setSetting("language", "en");
            var identity = DesktopIdentity.load(root.resolve("identity.properties"));
            window = edt(() -> {
                DesktopWindow result = new DesktopWindow(store, identity, root, () -> {
                    TestInquiry inquiry = new TestInquiry(inquiries.isEmpty()); inquiries.add(inquiry); return inquiry;
                });
                result.setVisible(true); return result;
            });
            DesktopWindow w = window; Strings text = new Strings("en");
            JButton scan = edt(() -> button(w, text.text("searchBluetooth")));
            edt(() -> { scan.doClick(); return null; });
            TestInquiry first = inquiries.get(0);
            if (!first.started.await(2, TimeUnit.SECONDS)) throw new AssertionError("Bluetooth scan did not start");
            edt(() -> { scan.doClick(); scan.doClick(); return null; });
            if (first.closed.getCount() != 0 || inquiries.size() != 2) throw new AssertionError("Stop failed to cancel and accept immediate restart");
            first.finish.countDown(); TestInquiry second = inquiries.get(1);
            if (!second.started.await(2, TimeUnit.SECONDS)) throw new AssertionError("Restart waited for the old inquiry timeout");
            edt(() -> { return null; });
            JList<?> devices = edt(() -> components(w).stream().filter(c -> c instanceof JList<?> l && text.text("bluetoothDevices").equals(l.getAccessibleContext().getAccessibleName())).map(c -> (JList<?>)c).findFirst().orElseThrow());
            if (!edt(() -> scan.getText().equals(text.text("stopSearch")) && devices.getModel().getSize() == 0)) throw new AssertionError("Stale completion reset or populated the new scan");
            second.finish.countDown();
            await(() -> devices.getModel().getSize() == 1 && ((DesktopBluetooth.Device)devices.getModel().getElementAt(0)).name().equals("Current scan"), "Restart did not publish fresh results");
            edt(() -> { scan.doClick(); return null; });
            TestInquiry third = inquiries.get(2);
            if (!third.started.await(2, TimeUnit.SECONDS)) throw new AssertionError("Third scan did not start");
            edt(() -> { button(w, text.text("stopAll")).doClick(); return null; });
            if (third.closed.getCount() != 0) throw new AssertionError("Stop all left the Bluetooth inquiry running");
            edt(() -> { scan.doClick(); return null; });
            TestInquiry fourth = inquiries.get(3);
            if (!fourth.started.await(2, TimeUnit.SECONDS)) throw new AssertionError("Restart after Stop all failed");
            edt(() -> { w.dispatchEvent(new WindowEvent(w, WindowEvent.WINDOW_CLOSING)); return null; });
            if (fourth.closed.getCount() != 0) throw new AssertionError("Exit left the Bluetooth inquiry running");
            await(() -> !w.isDisplayable(), "Bluetooth scan prevented exit");
            System.out.println("GuiTests: Bluetooth Stop, immediate restart, stale completion, Stop all and exit cancellation passed");
        } finally {
            for (TestInquiry inquiry : inquiries) { inquiry.close(); inquiry.finish.countDown(); }
            DesktopWindow w = window;
            if (w != null) { edt(() -> { if (w.isDisplayable()) w.dispatchEvent(new WindowEvent(w, WindowEvent.WINDOW_CLOSING)); return null; }); await(() -> !w.isDisplayable(), "Scan test cleanup failed"); }
        }
    }
    private static void startupUsesSavedLanguage(Path root) throws Exception {
        String previousData = System.getProperty("wozai.dataDir"); JDialog startup = null;
        try (DesktopStore locked = new DesktopStore(root)) {
            locked.setSetting("language", "en");
            System.setProperty("wozai.dataDir", root.toString()); Locale.setDefault(Locale.Category.DISPLAY, Locale.SIMPLIFIED_CHINESE);
            Main.main(new String[0]);
            await(() -> Arrays.stream(Window.getWindows()).anyMatch(window -> window instanceof JDialog && window.isVisible()), "Duplicate startup did not show an error");
            startup = edt(() -> Arrays.stream(Window.getWindows()).filter(window -> window instanceof JDialog && window.isVisible()).map(window -> (JDialog)window).findFirst().orElseThrow());
            JDialog dialog = startup;
            if (!edt(() -> dialog.getTitle().equals("NearbyIM") && components(dialog).stream().anyMatch(c -> c instanceof JTextArea a && a.getText().contains("Another NearbyIM") && a.getText().contains("Local data folder")))) throw new AssertionError("Startup error ignored the saved English language");
            edt(() -> { button(dialog, "Got it").doClick(); return null; });
        } finally {
            if (previousData == null) System.clearProperty("wozai.dataDir"); else System.setProperty("wozai.dataDir", previousData);
            JDialog dialog = startup; if (dialog != null) edt(() -> { dialog.dispose(); return null; });
        }
    }
}
