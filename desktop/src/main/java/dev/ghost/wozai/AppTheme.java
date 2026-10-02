package dev.ghost.wozai;

import com.formdev.flatlaf.*;
import javax.swing.*;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/** Android MainActivity's shared visual tokens, on accessible Swing controls. */
final class AppTheme {
    static Color background, surface, ink, muted, accent, accentInk, tonal, line;
    static boolean dark;
    static void install(boolean useDark) {
        dark = useDark;
        background = color(dark ? "101619" : "FAFCFA"); surface = color(dark ? "222B2F" : "EEF3EF");
        ink = color(dark ? "F1F5F3" : "17211C"); muted = color(dark ? "9AA8AD" : "58675F");
        accent = color(dark ? "82D8BA" : "246B4E"); accentInk = color(dark ? "103B2E" : "FFFFFF");
        tonal = color(dark ? "25453A" : "DDF4E7"); line = color(dark ? "33413D" : "E3EAE5");
        if (dark) FlatDarkLaf.setup(); else FlatLightLaf.setup();
        UIManager.put("defaultFont", new Font(Font.DIALOG, Font.PLAIN, 15));
        for (String type : new String[]{"Panel", "Viewport", "TabbedPane", "List", "TextArea", "ScrollPane"}) UIManager.put(type + ".background", background);
        for (String type : new String[]{"Label", "Button", "TextField", "TextArea", "List", "ComboBox", "TabbedPane"}) UIManager.put(type + ".foreground", ink);
        UIManager.put("Component.accentColor", accent); UIManager.put("Component.focusColor", accent);
        UIManager.put("Component.borderColor", line); UIManager.put("Component.arc", 20);
        UIManager.put("Button.arc", 24); UIManager.put("TextComponent.arc", 28);
        UIManager.put("Button.background", surface); UIManager.put("Button.focusedBackground", tonal);
        UIManager.put("Button.default.background", accent); UIManager.put("Button.default.foreground", accentInk);
        UIManager.put("TextField.background", surface); UIManager.put("ComboBox.background", surface);
        UIManager.put("List.selectionBackground", tonal); UIManager.put("List.selectionForeground", ink);
        UIManager.put("TabbedPane.selectedBackground", tonal); UIManager.put("TabbedPane.underlineColor", accent);
        UIManager.put("TabbedPane.tabHeight", 56); UIManager.put("TabbedPane.tabInsets", new Insets(8, 24, 8, 24));
        UIManager.put("ScrollBar.width", 10); UIManager.put("ScrollPane.smoothScrolling", true);
    }
    private static Color color(String hex) { return new Color(Integer.parseInt(hex, 16)); }
    static void primary(JButton button) { button.putClientProperty("wozai.primary",true); button.putClientProperty("FlatLaf.style", "background: " + hex(accent) + "; foreground: " + hex(accentInk) + "; borderWidth: 0; arc: 24"); }
    static void refreshPrimary(Component component) { if(component instanceof JButton button && Boolean.TRUE.equals(button.getClientProperty("wozai.primary")))primary(button); if(component instanceof Container container)for(Component child:container.getComponents())refreshPrimary(child); }
    static String hex(Color c) { return String.format("#%06x", c.getRGB() & 0xffffff); }
    static final class SurfacePanel extends JPanel {
        SurfacePanel(LayoutManager layout) { super(layout); setOpaque(false); }
        protected void paintComponent(Graphics g) { Graphics2D p=(Graphics2D)g.create(); p.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON); p.setColor(surface); p.fillRoundRect(0,0,getWidth(),getHeight(),28,28); p.dispose(); super.paintComponent(g); }
    }
    static final class Avatar extends JComponent {
        private final String name;
        Avatar(String name) { this.name = name; setPreferredSize(new Dimension(48,48)); }
        protected void paintComponent(Graphics g) {
            Graphics2D p = (Graphics2D) g.create(); p.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int size = Math.min(getWidth(), getHeight()); p.setColor(tonal); p.fillOval(0,0,size,size); p.setColor(accent);
            p.setFont(getFont().deriveFont(Font.BOLD, 20f)); String initial = name.isBlank() ? "?" : name.substring(0, name.offsetByCodePoints(0,1));
            FontMetrics m = p.getFontMetrics(); p.drawString(initial, (size-m.stringWidth(initial))/2, (size-m.getHeight())/2+m.getAscent()); p.dispose();
        }
    }
    private static final Map<String, BufferedImage> icons = new HashMap<>();
    static Icon icon(String name) { return new Icon() {
        public int getIconWidth() { return 24; } public int getIconHeight() { return 24; }
        public void paintIcon(Component c, Graphics g, int x, int y) {
            BufferedImage source = icons.computeIfAbsent(name, n -> { try { return ImageIO.read(AppTheme.class.getResource("icons/" + n + ".png")); } catch (IOException e) { throw new IllegalStateException(e); } });
            BufferedImage tinted = new BufferedImage(96,96,BufferedImage.TYPE_INT_ARGB); Graphics2D t = tinted.createGraphics(); t.drawImage(source,0,0,null); t.setComposite(AlphaComposite.SrcIn); t.setColor(c.isEnabled() ? c.getForeground() : muted); t.fillRect(0,0,96,96); t.dispose();
            Graphics2D p=(Graphics2D)g.create(); p.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC); p.drawImage(tinted,x,y,24,24,null); p.dispose();
        }
    }; }
}
