package dev.ghost.wozai;
import javax.swing.*;
import java.awt.*;
import java.nio.file.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
public final class EmojiPickerTests {
    private static <T>T edt(Callable<T> f)throws Exception{FutureTask<T> t=new FutureTask<>(f);SwingUtilities.invokeAndWait(t);return t.get();}
    private static <T>T field(Object o,String name,Class<T> type)throws Exception{var f=o.getClass().getDeclaredField(name);f.setAccessible(true);return type.cast(f.get(o));}
    public static void main(String[] args)throws Exception{
        JFrame owner=edt(JFrame::new);JTextArea draft=new JTextArea("left REPLACE right");String[] recent={""};
        try{
            for(String lang:new String[]{"en","zh-Hans","zh-Hant","ja","ko"}){
                var strings=new Strings(lang);EmojiPicker dialog=edt(()->{draft.setText("left REPLACE right");draft.select(5,12);var p=new EmojiPicker(owner,strings,recent[0],v->recent[0]=v,draft::replaceSelection);p.setVisible(true);return p;});
                try{
                    JList<?> grid=field(dialog,"grid",JList.class);JTextField query=field(dialog,"query",JTextField.class);JButton insert=field(dialog,"insert",JButton.class);
                    long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
                    while(!edt(()->grid.getModel().getSize()>0)&&System.nanoTime()<deadline)Thread.sleep(20);
                    if(!edt(()->grid.getModel().getSize()>0))throw new AssertionError("Catalog did not load");
                    edt(()->{query.setText("❤️");return null;});if(!edt(()->grid.getModel().getSize()>0))throw new AssertionError("Heart search");
                    edt(()->{insert.doClick();insert.doClick();return null;});
                    if(!draft.getText().equals("left ❤️❤️ right"))throw new AssertionError(draft.getText());
                    if(!dialog.isVisible()||recent[0].isEmpty())throw new AssertionError("Repeated insertion/recents");
                    edt(()->{query.setText("zz_no_result_99");return null;});if(edt(insert::isEnabled))throw new AssertionError("Empty selection enabled");
                    edt(()->{query.setText("");return null;});
                    if(args.length>0){Path file=Path.of(args[0]+"-"+lang+".png");Files.createDirectories(file.toAbsolutePath().getParent());Rectangle bounds=edt(dialog::getBounds);ImageIO.write(new Robot().createScreenCapture(bounds),"png",file.toFile());}
                }finally{edt(()->{dialog.dispose();return null;});}
            }
            System.out.println("EmojiPickerTests: native five-language grid, exact selection replacement, repeated insertion, empty search and recents passed");
        }finally{edt(()->{owner.dispose();return null;});}
    }
}
