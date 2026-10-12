package dev.ghost.wozai;

import dev.ghost.nearbyim.core.IdentityVerification;
import dev.ghost.nearbyim.verification.QrCodes;
import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import javax.imageio.ImageIO;

/** A human check of the exact long-term identities authenticated by this connection. */
final class SecurityDialog extends JDialog {
    private final DesktopClient client;
    private final IdentityVerification snapshot;
    private final Strings strings;
    private final List<Runnable> translations = new ArrayList<>();
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(0, 1, 15, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(2), r -> { Thread t = new Thread(r, "identity-qr-image"); t.setDaemon(true); return t; });
    private final JLabel connection = new JLabel(), identity = new JLabel(), notice = new JLabel(" ");
    private final JButton mark = new JButton(), read = new JButton(), export;
    private final JTextArea code, explanation, localOnly;
    private final JLabel qr;
    private BufferedImage qrImage;
    private boolean[] qrCells;
    private String noticeKey = "";
    private boolean pending;

    SecurityDialog(JFrame owner, DesktopClient client, IdentityVerification snapshot, Strings strings, String name) {
        super(owner, strings.text("verificationTitle"), false);
        this.client = client; this.snapshot = snapshot; this.strings = strings;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        JPanel content = new VerificationContent(); content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(BorderFactory.createEmptyBorder(20, 24, 24, 24));
        JLabel peer = new JLabel(name); peer.putClientProperty("html.disable", true);
        peer.setFont(peer.getFont().deriveFont(Font.BOLD, 20));peer.setMinimumSize(new Dimension(0,peer.getPreferredSize().height));
        JPanel heading = new JPanel(new BorderLayout(8, 0)); heading.add(peer);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.TRAILING, 4, 0));
        actions.add(icon("content_copy", "verificationCopy", () -> {
            try { Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(snapshot.code.display()), null); notice("verificationCopyDone"); }
            catch (RuntimeException unavailable) { notice("verificationReadFailed"); }
        }));
        export = icon("file_download", "verificationSaveQr", this::export); actions.add(export);
        heading.add(actions, BorderLayout.LINE_END); content.add(heading);
        connection.setIcon(AppTheme.icon("lock")); content.add(connection); content.add(identity);
        content.add(Box.createVerticalStrut(18));
        AppTheme.SurfacePanel card = new AppTheme.SurfacePanel(new BorderLayout(0, 16));
        card.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
        qr = new JLabel() { protected void paintComponent(Graphics g) {
            super.paintComponent(g); if (qrCells == null) return;
            int width=(int)Math.sqrt(qrCells.length),scale=Math.min(Math.min(getWidth(),getHeight()),320)/width;if(scale<1)return;
            int edge=width*scale,left=(getWidth()-edge)/2,top=(getHeight()-edge)/2;Color previous=g.getColor();g.setColor(Color.WHITE);g.fillRect(left,top,edge,edge);g.setColor(Color.BLACK);
            for(int y=0;y<width;y++)for(int x=0;x<width;x++)if(qrCells[y*width+x])g.fillRect(left+x*scale,top+y*scale,scale,scale);g.setColor(previous);
        }};
        qr.setPreferredSize(new Dimension(240, 240)); qr.setMinimumSize(new Dimension(128, 128));
        card.add(qr, BorderLayout.NORTH);
        code = text(snapshot.code.display()); code.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 18));
        code.setForeground(AppTheme.ink); card.add(code, BorderLayout.CENTER);
        code.addComponentListener(new java.awt.event.ComponentAdapter() { public void componentResized(java.awt.event.ComponentEvent e) {
            int charWidth = Math.max(1, code.getFontMetrics(code.getFont()).charWidth('0'));
            int groups = Math.max(1, (code.getWidth()+charWidth)/(9*charWidth));
            int rows = 2*((4+groups-1)/groups); if (code.getRows()!=rows) code.setRows(rows);
        }});
        AppTheme.primary(mark); mark.addActionListener(e -> {
            if (JOptionPane.showConfirmDialog(this, strings.text("verificationConfirm"), strings.text("verifyIdentity"), JOptionPane.OK_CANCEL_OPTION)==JOptionPane.OK_OPTION) save(null);
        }); card.add(mark, BorderLayout.SOUTH); content.add(card);
        content.add(Box.createVerticalStrut(16)); read.setIcon(AppTheme.icon("qr_code_scanner"));
        read.addActionListener(e -> chooseImage()); read.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(read); content.add(Box.createVerticalStrut(12)); content.add(notice);
        explanation = text(strings.text("verificationExplain")); localOnly = text(strings.text("verificationLocalOnly"));
        content.add(explanation); content.add(Box.createVerticalStrut(12)); content.add(localOnly);
        for(Component child:content.getComponents())if(child instanceof JComponent component)component.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.setMinimumSize(new Dimension(0,card.getMinimumSize().height));
        setContentPane(AppTheme.scroll(content));
        Rectangle screen = owner.getGraphicsConfiguration().getBounds();
        setSize(Math.min(620, screen.width-32), Math.min(760, screen.height-64));
        setMinimumSize(new Dimension(320, 280)); setLocationRelativeTo(owner);
        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        translate();
        submit(() -> {
            BufferedImage generated = image(snapshot, 512);
            boolean[] cells=QrCodes.modules(snapshot.code.qrPayload());
            SwingUtilities.invokeLater(() -> { if (worker.isShutdown()) return; qrImage = generated;qrCells=cells; qr.repaint(); });
        });
    }

    private JButton icon(String icon, String key, Runnable action) {
        JButton button = new JButton(AppTheme.icon(icon)); button.setPreferredSize(new Dimension(44, 44));
        button.addActionListener(e -> action.run());
        translations.add(() -> { String label = strings.text(key); button.setToolTipText(label); button.getAccessibleContext().setAccessibleName(label); });
        return button;
    }
    private static JTextArea text(String value) {
        JTextArea area = new JTextArea(value); area.setEditable(false); area.setLineWrap(true); area.setWrapStyleWord(true);
        area.setOpaque(false); area.setForeground(AppTheme.muted); area.setRows(Math.max(2, value.length()/48+1));area.setMinimumSize(new Dimension(0,0));return area;
    }
    private static BufferedImage image(IdentityVerification state, int edge) {
        boolean[] pixels = QrCodes.encode(state.code.qrPayload(), edge);
        BufferedImage image = new BufferedImage(edge, edge, BufferedImage.TYPE_INT_RGB);
        int[] rgb = new int[pixels.length]; for (int i=0; i<rgb.length; i++) rgb[i]=pixels[i]?0xff000000:0xffffffff;
        image.setRGB(0, 0, edge, edge, rgb, 0, edge); return image;
    }
    void translate() {
        setTitle(strings.text("verificationTitle")); translations.forEach(Runnable::run);
        qr.getAccessibleContext().setAccessibleName(strings.text("verificationQrImage"));
        mark.setText(strings.text("verificationMark")); read.setText(strings.text("verificationImage"));
        explanation.setText(strings.text("verificationExplain")); localOnly.setText(strings.text("verificationLocalOnly"));
        notice(noticeKey); refresh();
    }
    void refreshTheme() {
        SwingUtilities.updateComponentTreeUI(this); AppTheme.refreshPrimary(getContentPane());
        code.setForeground(AppTheme.ink); connection.setForeground(AppTheme.accent);
        for (JComponent label : List.of(identity, notice, explanation, localOnly)) label.setForeground(AppTheme.muted);
    }
    void refresh() {
        boolean active = client.verificationCurrent(snapshot), verified = active && client.identityVerified(snapshot.peerId);
        connection.setText(strings.text(active?"encryptionActive":"verificationConnectionChanged"));
        identity.setText(strings.text(verified?"identityVerified":"identityUnverified"));
        mark.setEnabled(active&&!pending&&!verified); read.setEnabled(active&&!pending); export.setEnabled(!pending);
    }
    private void notice(String key) { noticeKey=key; notice.setText(key.isEmpty()?" ":strings.text(key)); }
    private void save(String scanned) {
        if (!client.verificationCurrent(snapshot)) { notice("verificationConnectionChanged"); refresh(); return; }
        if (scanned!=null&&!snapshot.code.matchesQr(scanned)) { notice("verificationMismatch"); return; }
        pending=true; refresh();
        client.verifyIdentity(snapshot, scanned).whenComplete((ok, error) -> SwingUtilities.invokeLater(() -> {
            if (!isDisplayable()) return; pending=false;
            notice(error!=null?"storageFailure":Boolean.TRUE.equals(ok)&&client.verificationCurrent(snapshot)?"verificationDone":"verificationConnectionChanged"); refresh();
        }));
    }
    private void chooseImage() {
        if (JOptionPane.showConfirmDialog(this, strings.text("verificationExplain"), strings.text("verificationImage"), JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION) return;
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(strings.text("verificationQrImage"), "png", "jpg", "jpeg"));
        if (chooser.showOpenDialog(this)!=JFileChooser.APPROVE_OPTION) return;
        readImage(chooser.getSelectedFile().toPath());
    }
    private void readImage(Path file) {
        pending=true; notice("verificationReading"); refresh();
        submit(() -> {
            String decoded=null;
            try { if (Files.size(file)<=32L*1024*1024) { BufferedImage image=AttachmentImages.read(file, 1024);
                if (image!=null) { int w=image.getWidth(), h=image.getHeight(); decoded=QrCodes.decode(image.getRGB(0,0,w,h,null,0,w), w, h); }
            }} catch (IOException|RuntimeException invalid) { /* An unreadable image cannot confirm an identity. */ }
            String result=decoded; SwingUtilities.invokeLater(() -> {
                if (!isDisplayable()) return; pending=false; refresh();
                if (result==null) notice("verificationReadFailed"); else save(result);
            });
        });
    }
    private void export() {
        JFileChooser chooser=new JFileChooser(); chooser.setSelectedFile(new java.io.File("wozai-safety-code.png"));
        if (chooser.showSaveDialog(this)!=JFileChooser.APPROVE_OPTION) return; Path file=chooser.getSelectedFile().toPath();
        if (Files.exists(file)&&JOptionPane.showConfirmDialog(this, strings.text("attachmentOverwrite"), strings.text("verificationSaveQr"), JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION) return;
        pending=true; refresh();
        submit(() -> {
            boolean success=false; try { success=ImageIO.write(image(snapshot,512), "PNG", file.toFile()); } catch (IOException|RuntimeException error) { /* Report on the UI thread. */ }
            boolean saved=success; SwingUtilities.invokeLater(() -> { if (!isDisplayable()) return; pending=false; if (!saved) notice("verificationReadFailed"); refresh(); });
        });
    }
    private void submit(Runnable action) {
        try { worker.execute(action); }
        catch (RejectedExecutionException stopped) { pending=false; notice("verificationReadFailed"); refresh(); }
    }
    public void dispose() { worker.shutdownNow(); qrImage=null;qrCells=null; super.dispose(); }
    private static final class VerificationContent extends JPanel implements Scrollable {
        public Dimension getPreferredScrollableViewportSize(){return getPreferredSize();}
        public int getScrollableUnitIncrement(Rectangle visible,int orientation,int direction){return 24;}
        public int getScrollableBlockIncrement(Rectangle visible,int orientation,int direction){return Math.max(24,visible.height-24);}
        public boolean getScrollableTracksViewportWidth(){return true;}
        public boolean getScrollableTracksViewportHeight(){return false;}
    }
}
