package dev.ghost.wozai;

import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import javax.swing.*;

/** Real viewport, wheel and control geometry checks for the reported Windows UI issues. */
public final class LayoutTests {
    private static <T> T edt(Callable<T> work) throws Exception { FutureTask<T> task=new FutureTask<>(work); SwingUtilities.invokeAndWait(task); return task.get(); }
    private static List<Component> all(Component root) { List<Component> result=new ArrayList<>(); result.add(root); if(root instanceof Container c)for(Component child:c.getComponents())result.addAll(all(child)); return result; }
    private static final List<String> failures=new ArrayList<>();
    private static void check(boolean value,String message) { if(!value)failures.add(message); }
    private static JTabbedPane navigation(DesktopWindow w) { return all(w).stream().filter(c -> c instanceof JTabbedPane t && t.getTabCount()==3).map(c -> (JTabbedPane)c).findFirst().orElseThrow(); }
    private static JComboBox<?> size(DesktopWindow w) { return all(w).stream().filter(c -> c instanceof JComboBox<?> b && "Standard".equals(b.getItemAt(0))).map(c -> (JComboBox<?>)c).findFirst().orElseThrow(); }
    private static JButton button(Component root,String text) { return all(root).stream().filter(c -> c instanceof JButton b && text.equals(b.getText())).map(c -> (JButton)c).findFirst().orElseThrow(); }
    private static void capture(Robot robot,Window window,String path) throws Exception { robot.waitForIdle(); Thread.sleep(150); Path p=Path.of(path); Files.createDirectories(p.toAbsolutePath().getParent()); ImageIO.write(robot.createScreenCapture(edt(window::getBounds)),"png",p.toFile()); }
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        Path root=Files.createTempDirectory("nearbyim-layout-"); DesktopWindow window=null;
        try(var store=new DesktopStore(root)) {
            store.setSetting("language","en"); store.setSetting("nickname","Layout device"); String id=UUID.randomUUID().toString(); store.peer(new DesktopStore.Peer(id,"林的平板","",""));
            var identity=DesktopIdentity.load(root.resolve("identity.properties"));
            window=edt(() -> { AppTheme.install(false); var w=new DesktopWindow(store,identity,root); w.setLocation(20,20); w.setSize(760,540); w.setVisible(true); return w; }); var w=window;
            Thread.sleep(250);
            edt(() -> {
                var composer=all(w).stream().filter(c -> c instanceof JTextArea a && "Type a message".equals(a.getAccessibleContext().getAccessibleName())).map(c -> (JTextArea)c).findFirst().orElseThrow();
                var send=all(w).stream().filter(c -> c instanceof JButton b && "Send".equals(b.getText())).map(c -> (JButton)c).findFirst().orElseThrow();
                var capsule=composer.getParent().getParent().getParent();
                check(Math.abs(capsule.getHeight()-send.getHeight())<=1,"Composer and send button have different heights: "+capsule.getHeight()+" / "+send.getHeight());
                check(w.getJMenuBar()==null,"Header repeats the brand in a menu bar");
                check(!Set.of("Dialog","Serif","SansSerif").contains(composer.getFont().getFamily(Locale.ROOT)),"Chinese UI still relies on a logical font fallback: "+composer.getFont());
                check(composer.getFont().getFamily(Locale.ENGLISH).equals("Noto Sans CJK SC"),"The bundled Chinese font was not applied: "+composer.getFont());
                try(var source=AppTheme.class.getResourceAsStream("fonts/NotoSansCJKsc-Regular.otf")) { check(source!=null && Font.createFont(Font.TRUETYPE_FONT,source).canDisplayUpTo("我在 林的平板 使用说明 聊天 附近 设置")==-1,"Bundled font is absent or lacks Chinese glyphs"); }
                System.out.println("UI font: "+composer.getFont().getFamily(Locale.ENGLISH));
                var history=all(w).stream().filter(c -> c instanceof JList<?> list && "Chats".equals(list.getAccessibleContext().getAccessibleName())).map(c -> (JList<DesktopStore.Peer>)c).findFirst().orElseThrow();
                Component row=history.getCellRenderer().getListCellRendererComponent(history,history.getModel().getElementAt(0),0,true,false); row.setSize(300,90); if(row instanceof Container c)c.doLayout();
                BufferedImage pixels=new BufferedImage(300,90,BufferedImage.TYPE_INT_RGB); row.paint(pixels.getGraphics());
                check(pixels.getRGB(0,0)==AppTheme.background.getRGB() && pixels.getRGB(150,4)==AppTheme.tonal.getRGB(),"Selected conversation highlight is rectangular");
                navigation(w).setSelectedIndex(2); return null;
            });
            Thread.sleep(150);
            JTextField nickname=edt(() -> all(w).stream().filter(c -> c instanceof JTextField f && "Layout device".equals(f.getText())).map(c -> (JTextField)c).findFirst().orElseThrow());
            JScrollPane scroll=edt(() -> (JScrollPane)SwingUtilities.getAncestorOfClass(JScrollPane.class,nickname));
            for(int textSize:new int[]{0,2}) {
                edt(() -> { size(w).setSelectedIndex(textSize); return null; }); Thread.sleep(180);
                edt(() -> {
                    check(!scroll.getHorizontalScrollBar().isVisible(),"Settings has horizontal overflow at text size "+textSize);
                    check(scroll.getViewport().getViewSize().width==scroll.getViewport().getExtentSize().width,"Settings content does not track the viewport width");
                    check(scroll.getVerticalScrollBar().getUnitIncrement(1)>=16,"Wheel increment is too small: "+scroll.getVerticalScrollBar().getUnitIncrement(1));
                    check(nickname.getHeight()<=80,"A settings control grows beyond a readable row height: "+nickname.getHeight());
                    Component view=scroll.getViewport().getView(); int before=view.getPreferredSize().height;
                    for(int i=0;i<20;i++) { ((Container)view).doLayout(); all(view).stream().filter(c -> c instanceof Container).forEach(c -> ((Container)c).doLayout()); }
                    check(view.getPreferredSize().height==before,"Repeated layout inflates settings row heights");
                    return null;
                });
            }
            edt(() -> { size(w).setSelectedIndex(0); scroll.getVerticalScrollBar().setValue(0); return null; }); Thread.sleep(150);
            Rectangle bounds=edt(() -> { Point p=scroll.getViewport().getLocationOnScreen(); return new Rectangle(p,scroll.getViewport().getSize()); });
            Robot robot=new Robot(); robot.mouseMove(bounds.x+bounds.width/2,bounds.y+bounds.height/2); robot.mouseWheel(6); Thread.sleep(350);
            check(edt(() -> scroll.getVerticalScrollBar().getValue())>=80,"Six wheel ticks barely move settings");
            check(edt(() -> all(w).stream().anyMatch(c -> c instanceof JButton b && "How to use".equals(b.getText()))),"Settings has no Android-style usage instructions");
            check(edt(() -> all(w).stream().anyMatch(c -> c instanceof JButton b && "About NearbyIM".equals(b.getText()))),"Settings has no About NearbyIM entry");
            JComboBox<?> languages=edt(() -> all(w).stream().filter(c -> c instanceof JComboBox<?> b && "English".equals(b.getItemAt(1))).map(c -> (JComboBox<?>)c).findFirst().orElseThrow());
            edt(() -> { languages.setSelectedIndex(0); return null; }); Thread.sleep(250);
            edt(() -> { scroll.getVerticalScrollBar().setValue(0); return null; });
            if(args.length>0)capture(robot,w,args[0]);
            for(String title:new String[]{"使用说明","关于我在"}) {
                edt(() -> { button(w,title).doClick(); return null; }); Thread.sleep(150);
                JDialog dialog=edt(() -> Arrays.stream(w.getOwnedWindows()).filter(c -> c instanceof JDialog d && d.isVisible() && title.equals(d.getTitle())).map(c -> (JDialog)c).findFirst().orElseThrow());
                edt(() -> {
                    JTextArea body=all(dialog).stream().filter(c -> c instanceof JTextArea a && !a.getText().isBlank()).map(c -> (JTextArea)c).findFirst().orElseThrow();
                    check(body.getText().contains("局域网") && body.getText().contains("蓝牙"),title+" is missing Android's connection guidance");
                    JScrollPane info=(JScrollPane)SwingUtilities.getAncestorOfClass(JScrollPane.class,body);
                    check(!info.getHorizontalScrollBar().isVisible() && body.getHeight()<2000,title+" has unreadable or overflowing text"); return null;
                });
                if(args.length>0)capture(robot,dialog,args[0].replace(".png",title.equals("使用说明")?"-help.png":"-about.png"));
                edt(() -> { button(dialog,"知道了").doClick(); check(!dialog.isVisible(),"Information close button did not close the dialog"); return null; });
            }
            edt(() -> { scroll.getVerticalScrollBar().setValue(scroll.getVerticalScrollBar().getMaximum()); return null; });
            if(args.length>0)capture(robot,w,args[0].replace(".png","-bottom.png"));
            edt(() -> { navigation(w).setSelectedIndex(0); all(w).stream().filter(c -> c instanceof JList<?> l && "聊天".equals(l.getAccessibleContext().getAccessibleName())).forEach(c -> ((JList<?>)c).setSelectedIndex(0)); return null; }); Thread.sleep(150);
            if(args.length>0)capture(robot,w,args[0].replace(".png","-chat.png"));
            if(!failures.isEmpty())throw new AssertionError(String.join("\n",failures));
            System.out.println("LayoutTests: font, single header, round chat selection, equal composer height, responsive settings, large text and real wheel passed");
        } finally {
            var w=window; if(w!=null) { edt(() -> { w.dispatchEvent(new WindowEvent(w,WindowEvent.WINDOW_CLOSING)); return null; }); for(int i=0;i<50&&edt(w::isDisplayable);i++)Thread.sleep(20); }
        }
    }
}
