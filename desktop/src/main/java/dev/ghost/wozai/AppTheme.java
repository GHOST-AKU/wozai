package dev.ghost.wozai;

import com.formdev.flatlaf.*;
import javax.swing.*;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import dev.ghost.nearbyim.core.Identicon;

/** Android MainActivity's shared visual tokens, on accessible Swing controls. */
final class AppTheme {
    static Color background, surface, ink, muted, accent, accentInk, tonal, line;
    static boolean dark;
    private static String fontFamily;
    static void install(boolean useDark) {
        tintedIcons.clear();
        dark = useDark;
        background = color(dark ? "101619" : "FAFCFA"); surface = color(dark ? "222B2F" : "EEF3EF");
        ink = color(dark ? "F1F5F3" : "17211C"); muted = color(dark ? "9AA8AD" : "58675F");
        accent = color(dark ? "82D8BA" : "246B4E"); accentInk = color(dark ? "103B2E" : "FFFFFF");
        tonal = color(dark ? "25453A" : "DDF4E7"); line = color(dark ? "33413D" : "E3EAE5");
        FlatLaf.setPreferredFontFamily(fontFamily());
        if (dark) FlatDarkLaf.setup(); else FlatLightLaf.setup();
        // Swing caches this hint when each UI is installed. Windows LCD text
        // rendering creates RGB fringes; keep antialiasing, using grayscale.
        if (DesktopIdentity.windows()) UIManager.put(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        ui("defaultFont", new javax.swing.plaf.FontUIResource(com.formdev.flatlaf.util.FontUtils.getCompositeFont(fontFamily(),Font.PLAIN,15)));
        for (String type : new String[]{"Panel", "Viewport", "TabbedPane", "List", "TextArea", "ScrollPane"}) ui(type + ".background", background);
        for (String type : new String[]{"Label", "Button", "TextField", "TextArea", "List", "ComboBox", "TabbedPane"}) ui(type + ".foreground", ink);
        ui("Component.accentColor", accent); ui("Component.focusColor", accent);
        ui("Component.borderColor", line); ui("Component.arc", 20);
        ui("Button.arc", 24); ui("TextComponent.arc", 28);
        ui("Button.minimumHeight",44); ui("TextField.minimumHeight",44); ui("ComboBox.minimumHeight",44);
        ui("Button.background", surface); ui("Button.focusedBackground", tonal);
        ui("Button.default.background", accent); ui("Button.default.foreground", accentInk);
        ui("TextField.background", surface); ui("ComboBox.background", surface);
        ui("List.selectionBackground", tonal); ui("List.selectionForeground", ink);
        ui("TabbedPane.selectedBackground", tonal); ui("TabbedPane.underlineColor", accent);
        ui("TabbedPane.tabHeight", 56); ui("TabbedPane.tabInsets", new Insets(8, 24, 8, 24));
        ui("ScrollBar.width", 10); ui("ScrollPane.smoothScrolling", true);
    }
    private static String fontFamily() {
        if(fontFamily==null) {
            try {
                String family = null;
                for (String weight : new String[]{"Regular", "Bold"}) {
                    try (var source = AppTheme.class.getResourceAsStream("fonts/NotoSansCJKsc-" + weight + ".otf")) {
                        if (source == null) throw new IOException("Bundled font weight is missing: " + weight);
                        Font font = Font.createFont(Font.TRUETYPE_FONT, source);
                        GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(font);
                        family = font.getFamily(java.util.Locale.ENGLISH);
                    }
                }
                // Register every weight before FlatLaf/StyleContext caches composite fonts.
                fontFamily = family;
            } catch(IOException|FontFormatException e) { throw new IllegalStateException("Unable to load the bundled font", new dev.ghost.nearbyim.i18n.LocalizedIOException(dev.ghost.nearbyim.i18n.UiText.of("fontLoadFailed"), e)); }
        }
        return fontFamily;
    }
    static JScrollPane scroll(Component view) { JScrollPane scroll=new JScrollPane(view); scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER); scroll.setBorder(BorderFactory.createEmptyBorder()); scroll.getVerticalScrollBar().setUnitIncrement(24); return scroll; }
    private static void ui(String key,Object value) { UIManager.put(key,value instanceof Color color ? new javax.swing.plaf.ColorUIResource(color) : value); }
    private static Color color(String hex) { return new Color(Integer.parseInt(hex, 16)); }
    static void primary(JButton button) { button.putClientProperty("wozai.primary",true); button.putClientProperty("FlatLaf.style", "background: " + hex(accent) + "; foreground: " + hex(accentInk) + "; borderWidth: 0; arc: "+(Boolean.TRUE.equals(button.getClientProperty("wozai.round"))?999:24)); }
    static void refreshPrimary(Component component) { if(component instanceof JComponent view && Boolean.TRUE.equals(view.getClientProperty("wozai.muted")))view.setForeground(muted); if(component instanceof JButton button && Boolean.TRUE.equals(button.getClientProperty("wozai.primary")))primary(button); if(component instanceof Container container)for(Component child:container.getComponents())refreshPrimary(child); }
    static String hex(Color c) { return String.format("#%06x", c.getRGB() & 0xffffff); }
    static final class SurfacePanel extends JPanel {
        SurfacePanel(LayoutManager layout) { super(layout); setOpaque(false); }
        protected void paintComponent(Graphics g) { Graphics2D p=(Graphics2D)g.create(); p.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON); p.setColor(surface); p.fillRoundRect(0,0,getWidth(),getHeight(),52,52); p.dispose(); super.paintComponent(g); }
    }
    static final class Avatar extends JComponent {
        private Identicon.Model model=Identicon.neutral();
        private java.lang.ref.WeakReference<BufferedImage> photo=new java.lang.ref.WeakReference<>(null);
        Avatar(String root) { this(root,null,Identicon.neutral()); }
        Avatar(String root,BufferedImage photo) { this(root,photo,Identicon.neutral()); }
        Avatar(String root,BufferedImage photo,Identicon.Model resolved) { set(root,photo,resolved);setPreferredSize(new Dimension(48,48)); }
        void set(String root,BufferedImage photo,Identicon.Model resolved) {
            model=resolved==null?Identicon.neutral():resolved;if(root==null||root.isEmpty()||model==Identicon.neutral()){model=Identicon.neutral();photo=null;}
            this.photo=new java.lang.ref.WeakReference<>(photo);putClientProperty("wozai.avatar.root",root==null?"":root);repaint();
        }
        @Override public javax.accessibility.AccessibleContext getAccessibleContext(){if(accessibleContext==null)accessibleContext=new AccessibleJComponent(){};return accessibleContext;}
        protected void paintComponent(Graphics g) {
            Graphics2D p = (Graphics2D) g.create(); p.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            try{
                int size=Math.min(getWidth(),getHeight()),x=(getWidth()-size)/2,y=(getHeight()-size)/2;boolean square=Boolean.TRUE.equals(getClientProperty("wozai.avatar.square"));
                BufferedImage image=photo.get();p.clip(square?new Rectangle(x,y,size,size):image==null?new java.awt.geom.RoundRectangle2D.Double(x,y,size,size,size*.22,size*.22):new java.awt.geom.Ellipse2D.Double(x,y,size,size));p.setColor(new Color(model.background(dark),true));p.fillRect(x,y,size,size);
                if(image!=null){p.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);p.drawImage(image,x,y,size,size,null);}
                else{p.setColor(new Color(model.foreground(dark),true));double cell=size/9.0;for(int row=0;row<7;row++)for(int col=0;col<7;col++)if(model.filled(col,row)){int left=x+(int)Math.round((col+1)*cell),top=y+(int)Math.round((row+1)*cell);p.fillRect(left,top,(int)Math.round((col+2)*cell)+x-left,(int)Math.round((row+2)*cell)+y-top);}}
            }finally{p.dispose();}
        }
    }
    private static final Map<String, BufferedImage> icons = new HashMap<>();
    private record TintKey(String name,int color) {}
    private static final Map<TintKey,BufferedImage> tintedIcons=new java.util.LinkedHashMap<>(64,0.75f,true){
        protected boolean removeEldestEntry(Map.Entry<TintKey,BufferedImage> entry){return size()>64;}
    };
    static Icon icon(String name) { return new Icon() {
        public int getIconWidth() { return 24; } public int getIconHeight() { return 24; }
        public void paintIcon(Component c, Graphics g, int x, int y) {
            BufferedImage source = icons.computeIfAbsent(name, n -> { try { return ImageIO.read(AppTheme.class.getResource("icons/" + n + ".png")); } catch (IOException e) { throw new IllegalStateException(e); } });
            Color color=c.isEnabled()?c.getForeground():muted;
            BufferedImage tinted=tintedIcons.computeIfAbsent(new TintKey(name,color.getRGB()),key->{
                BufferedImage image=new BufferedImage(96,96,BufferedImage.TYPE_INT_ARGB);Graphics2D t=image.createGraphics();
                try{t.drawImage(source,0,0,96,96,null);t.setComposite(AlphaComposite.SrcIn);t.setColor(color);t.fillRect(0,0,96,96);}finally{t.dispose();}
                return image;
            });
            Graphics2D p=(Graphics2D)g.create(); p.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC); p.drawImage(tinted,x,y,24,24,null); p.dispose();
        }
    }; }
}
