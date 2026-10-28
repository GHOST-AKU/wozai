package dev.ghost.wozai;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import dev.ghost.nearbyim.core.EmojiCatalog;
import java.nio.file.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
public final class EmojiPickerTests {
    private static <T>T edt(Callable<T> f)throws Exception{FutureTask<T> t=new FutureTask<>(f);SwingUtilities.invokeAndWait(t);return t.get();}
    private static <T>T field(Object o,String name,Class<T> type)throws Exception{var f=o.getClass().getDeclaredField(name);f.setAccessible(true);return type.cast(f.get(o));}
    private static java.util.List<Component> components(Component root){java.util.List<Component> out=new java.util.ArrayList<>();out.add(root);if(root instanceof Container c)for(Component child:c.getComponents())out.addAll(components(child));return out;}
    public static void main(String[] args)throws Exception{
        JFrame owner=edt(()->{AppTheme.install(false);return new JFrame();});JTextArea draft=new JTextArea("left REPLACE right");String[] recent={""};
        try{
            for(String lang:new String[]{"en","zh-Hans","zh-Hant","ja","ko"}){
                var strings=new Strings(lang);EmojiPicker dialog=edt(()->{draft.setText("left REPLACE right");draft.select(5,12);var p=new EmojiPicker(owner,strings,recent[0],v->recent[0]=v,draft::replaceSelection);p.setVisible(true);return p;});
                try{
                    JList<?> grid=field(dialog,"grid",JList.class);JTextField query=field(dialog,"query",JTextField.class);JButton insert=field(dialog,"insert",JButton.class);
                    if(!edt(()->insert.getFont().canDisplayUpTo(strings.text("emojiInsert"))==-1&&query.getFont().canDisplayUpTo(strings.text("emojiSearch"))==-1))throw new AssertionError("Localized picker controls have missing font glyphs: "+lang);
                    long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
                    while(!edt(()->grid.getModel().getSize()>0)&&System.nanoTime()<deadline)Thread.sleep(20);
                    if(!edt(()->grid.getModel().getSize()>0))throw new AssertionError("Catalog did not load");
                    edt(()->{query.setText("❤️");return null;});if(!edt(()->grid.getModel().getSize()>0))throw new AssertionError("Heart search");
                    edt(()->{JList<EmojiCatalog.Entry> typed=field(dialog,"grid",JList.class);Component cell=typed.getCellRenderer().getListCellRendererComponent(typed,typed.getModel().getElementAt(0),0,false,false);cell.setSize(64,56);BufferedImage image=new BufferedImage(64,56,BufferedImage.TYPE_INT_RGB);Graphics2D graphics=image.createGraphics();try{cell.paint(graphics);}finally{graphics.dispose();}int background=image.getRGB(8,8);boolean visible=false;for(int y=10;y<46;y++)for(int x=10;x<54;x++)visible|=image.getRGB(x,y)!=background;if(!visible)throw new AssertionError("Heart cell paints no visible glyph: "+cell.getFont());return null;});
                    edt(()->{insert.doClick();insert.doClick();return null;});
                    if(!draft.getText().equals("left ❤️❤️ right"))throw new AssertionError(draft.getText());
                    if(!dialog.isVisible()||recent[0].isEmpty())throw new AssertionError("Repeated insertion/recents");
                    edt(()->{query.setText("zz_no_result_99");return null;});if(edt(insert::isEnabled))throw new AssertionError("Empty selection enabled");
                    edt(()->{query.setText("");return null;});
                    if(lang.equals("en")){
                        edt(()->{query.setText("🧑‍🤝‍🧑");return null;});
                        JButton variants=field(dialog,"variants",JButton.class);
                        SwingUtilities.invokeLater(variants::doClick);
                        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);JDialog variantDialog=null;
                        while(System.nanoTime()<end&&variantDialog==null){variantDialog=edt(()->java.util.Arrays.stream(dialog.getOwnedWindows()).filter(w->w instanceof JDialog d&&d.isVisible()).map(w->(JDialog)w).findFirst().orElse(null));Thread.sleep(20);}
                        if(variantDialog==null)throw new AssertionError("Variant selector did not open");
                        JDialog opened=variantDialog;
                        try{if(!edt(()->components(opened).stream().anyMatch(c->c instanceof JList<?> list&&list.getModel().getSize()==26&&SwingUtilities.getAncestorOfClass(JScrollPane.class,list)!=null)))throw new AssertionError("Largest variant family must scroll");}
                        finally{edt(()->{opened.dispose();return null;});}
                        edt(()->{query.setText("");return null;});
                    }
                    if(args.length>0){Path file=Path.of(args[0]+"-"+lang+".png");Files.createDirectories(file.toAbsolutePath().getParent());Robot robot=new Robot();robot.waitForIdle();Rectangle bounds=edt(dialog::getBounds);ImageIO.write(robot.createScreenCapture(bounds),"png",file.toFile());}
                }finally{edt(()->{dialog.dispose();return null;});}
            }
            System.out.println("EmojiPickerTests: native five-language grid, exact selection replacement, repeated insertion, empty search and recents passed");
        }finally{edt(()->{owner.dispose();return null;});}
    }
}
