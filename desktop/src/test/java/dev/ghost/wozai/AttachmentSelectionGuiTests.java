package dev.ghost.wozai;
import javax.swing.*;
import java.awt.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Exercise the production native confirmation page, including its final immutable order. */
public final class AttachmentSelectionGuiTests {
    static List<Component> all(Component c){var items=new ArrayList<Component>();items.add(c);if(c instanceof Container p)for(Component child:p.getComponents())items.addAll(all(child));return items;}
    static JButton button(Component c,Strings s,String key){return all(c).stream().filter(v->v instanceof JButton b&&s.text(key).equals(b.getText())).map(v->(JButton)v).findFirst().orElseThrow();}
    public static void main(String[] args)throws Exception {
        Class<?> production;try{production=Class.forName("dev.ghost.wozai.AttachmentSelectionDialog");}catch(ClassNotFoundException e){throw new AssertionError("Native batch confirmation missing",e);}
        Path dir=Files.createTempDirectory("native-select-");Path a=dir.resolve("a.txt"),b=dir.resolve("b.txt"),c=dir.resolve("c.txt");Files.writeString(a,"first");Files.writeString(b,"second");Files.writeString(c,"third");
        AtomicReference<List<Path>> sent=new AtomicReference<>();
        try{SwingUtilities.invokeAndWait(()->{try{
            AppTheme.install(false);Strings s=new Strings("en");
            JDialog page=(JDialog)production.getDeclaredConstructor(Window.class,Strings.class,List.class,Consumer.class).newInstance(null,s,List.of(a,b,c),(Consumer<List<Path>>)sent::set);page.setVisible(true);
            JList<?> list=all(page).stream().filter(v->v instanceof JList<?>).map(v->(JList<?>)v).findFirst().orElseThrow();list.setSelectedIndex(2);button(page,s,"attachmentMoveUp").doClick();list.setSelectedIndex(0);button(page,s,"attachmentRemove").doClick();button(page,s,"send").doClick();
            if(!List.of(c,b).equals(sent.get()))throw new AssertionError("Native preview sent the wrong selection/order");
            page.dispose();
        }catch(Exception e){throw new RuntimeException(e);}});}finally{Files.deleteIfExists(a);Files.deleteIfExists(b);Files.deleteIfExists(c);Files.delete(dir);}
        System.out.println("AttachmentSelectionGuiTests: native reorder/remove/final selection and disposal passed");
    }
}
