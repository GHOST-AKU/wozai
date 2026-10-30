package dev.ghost.wozai;
import java.awt.*;
import java.awt.event.WindowEvent;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import javax.swing.*;

/** Exercises the production window's focused/offscreen/hidden read-receipt gate. */
public final class ReadReceiptGuiTests {
    private static <T>T edt(Callable<T> task)throws Exception{return ReceiptGuiTests.edt(task);}
    @SuppressWarnings("unchecked") private static <T>T field(Object value,String name)throws Exception{Field f=value.getClass().getDeclaredField(name);f.setAccessible(true);return(T)f.get(value);}
    private static void await(Callable<Boolean> condition,String message)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);while(!condition.call()){if(System.nanoTime()>end)throw new AssertionError(message);Thread.sleep(20);}}
    public static void main(String[] args)throws Exception {
        Path root=Files.createTempDirectory("wozai-read-gui-");String peer=UUID.randomUUID().toString();DesktopWindow window=null;JFrame cover=null;
        DesktopStore store=new DesktopStore(root);
        try {
            store.setSetting("language","en");store.setSetting("readReceipts","false");store.peer(new DesktopStore.Peer(peer,"History","",""));
            List<String> ids=new ArrayList<>();for(int i=0;i<40;i++){String id=UUID.randomUUID().toString();ids.add(id);store.receive(peer,id,"Visible text "+i+"\nSecond line",i);}
            DesktopIdentity.Identity identity=DesktopIdentity.load(root.resolve("identity.properties"));
            window=edt(()->{DesktopWindow w=new DesktopWindow(store,identity,root);w.setVisible(true);return w;});DesktopWindow w=window;
            DesktopClient client=edt(()->field(w,"client"));JList<?> history=edt(()->field(w,"history"));JTabbedPane tabs=edt(()->field(w,"tabs"));MessagePane pane=edt(()->field(w,"transcript"));
            await(()->edt(()->history.getModel().getSize()==1),"History did not load");edt(()->{history.setSelectedIndex(0);tabs.setSelectedIndex(2);return null;});
            await(()->edt(()->ReceiptGuiTests.children(w).stream().anyMatch(c->c instanceof JCheckBox b&&b.getText().equals("Read receipts"))),"Native privacy checkbox missing");
            edt(()->{ReceiptGuiTests.children(w).stream().filter(c->c instanceof JCheckBox b&&b.getText().equals("Read receipts")).map(c->(JCheckBox)c).findFirst().orElseThrow().doClick();return null;});
            await(client::readReceiptsEnabled,"Native checkbox did not persist enable");Thread.sleep(650);
            if(!store.pendingReads(peer).isEmpty())throw new AssertionError("Hidden conversation generated read facts");
            edt(()->{tabs.setSelectedIndex(0);w.toFront();w.requestFocus();return null;});await(()->edt(w::isFocused),"Production chat window has no focus");
            await(()->!store.pendingReads(peer).isEmpty(),"Focused native viewport did not persist reads");
            Set<String> visible=edt(()->new HashSet<>(pane.visibleIncomingText()));List<String> pending=store.pendingReads(peer);
            if(!visible.containsAll(pending)||pending.size()>=ids.size())throw new AssertionError("Offscreen rows were marked read");
            cover=edt(()->{JFrame f=new JFrame("Focus elsewhere");f.setSize(400,300);f.setLocation(0,0);f.setVisible(true);f.toFront();f.requestFocus();return f;});
            await(()->edt(()->!w.isFocused()),"Production window did not lose focus");
            String fresh=UUID.randomUUID().toString();store.receive(peer,fresh,"Arrived while another window is focused",100);client.refresh().get(3,TimeUnit.SECONDS);
            Thread.sleep(650);if(store.pendingReads(peer).contains(fresh))throw new AssertionError("Unfocused production window marked new text read");
            edt(()->{w.toFront();w.requestFocus();return null;});await(()->edt(w::isFocused),"Production window did not regain focus");
            await(()->store.pendingReads(peer).contains(fresh),"Regained focus did not observe displayed new text");
            System.out.println("ReadReceiptGuiTests: actual settings, hidden conversation, focused viewport, offscreen rows and lost/regained window focus passed");
        }finally{
            JFrame other=cover;if(other!=null)edt(()->{other.dispose();return null;});DesktopWindow w=window;
            if(w!=null){edt(()->{w.dispatchEvent(new WindowEvent(w,WindowEvent.WINDOW_CLOSING));return null;});await(()->edt(()->!w.isDisplayable()),"Read window cleanup failed");}
            else store.close();
            try(var paths=Files.walk(root)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}
        }
    }
}
