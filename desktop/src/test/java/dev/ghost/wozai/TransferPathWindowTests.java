package dev.ghost.wozai;

import dev.ghost.nearbyim.core.*;
import dev.ghost.nearbyim.i18n.UiText;
import java.awt.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javax.swing.*;

/** Exercise the window cache, not just a directly rendered MessagePane. */
public final class TransferPathWindowTests {
    public static void main(String[] args)throws Exception {
        Path root=Files.createTempDirectory("window-path-");Path ap=root.resolve("a"),bp=root.resolve("b");Files.createDirectories(ap);Files.createDirectories(bp);
        var ai=DesktopIdentity.load(ap.resolve("identity.properties"));var bi=DesktopIdentity.load(bp.resolve("identity.properties"));
        final DesktopClient[] receiver=new DesktopClient[1];final DesktopWindow[] window=new DesktopWindow[1];
        try(DesktopStore as=new DesktopStore(ap);DesktopStore bs=new DesktopStore(bp)) {
            receiver[0]=new DesktopClient(bs,bi,new DesktopClient.Listener(){public void changed(DesktopClient.State state){}public void request(DesktopClient.Request request){receiver[0].approve(request,true);}public void notice(UiText text){}});
            try(DesktopClient b=receiver[0]) {
                var listening=b.listen().get(5,TimeUnit.SECONDS);String endpoint=listening.endpoints().get(0);
                var peer=new DesktopStore.Peer(bi.id(),"path peer",bi.signer().publicKey(),endpoint);as.peer(peer);as.setSetting("language","en");
                AttachmentInfo info=AttachmentInfo.v2(UUID.randomUUID().toString(),"paused incoming.bin","application/octet-stream",1024,null,1);
                as.attachment(peer.id(),new AttachmentRecord(info,false,"paused",512));
                SwingUtilities.invokeAndWait(()->{try{AppTheme.install(false);window[0]=new DesktopWindow(as,ai,ap);window[0].setVisible(true);Method select=DesktopWindow.class.getDeclaredMethod("select",DesktopStore.Peer.class);select.setAccessible(true);select.invoke(window[0],peer);}catch(Exception e){throw new AssertionError(e);}});
                DesktopClient a=field(window[0],"client");
                until(()->path(window[0]).contains("disconnected"));
                a.connect(endpoint,bi.id()).get(5,TimeUnit.SECONDS);
                until(()->a.endToEndEncrypted(bi.id()));
                until(()->path(window[0]).contains("LAN"));
                a.disconnect().get(5,TimeUnit.SECONDS);until(()->path(window[0]).contains("disconnected"));
                System.out.println("TransferPathWindowTests: actual window cache updates an unchanged incoming paused task on connection and disconnect");
            }finally {
                if(window[0]!=null){shutdown(window[0]);until(()->!window[0].isDisplayable());}
            }
        }finally {
            if(window[0]!=null&&window[0].isDisplayable())shutdown(window[0]);
            try(var files=Files.walk(root)){for(Path p:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}
        }
    }
    @SuppressWarnings("unchecked")private static <T>T field(Object target,String name)throws Exception{Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return (T)field.get(target);}
    private static void shutdown(DesktopWindow window)throws Exception{SwingUtilities.invokeAndWait(()->{try{Method method=DesktopWindow.class.getDeclaredMethod("shutdown");method.setAccessible(true);method.invoke(window);}catch(Exception e){throw new AssertionError(e);}});}
    private static String path(Container root)throws Exception{String[] value={""};SwingUtilities.invokeAndWait(()->value[0]=find(root));return value[0];}
    private static String find(Container root){for(Component child:root.getComponents()){if(child instanceof JLabel label&&"attachmentPath".equals(label.getName())&&label.isVisible())return label.getText();if(child instanceof Container inner){String found=find(inner);if(!found.isEmpty())return found;}}return "";}
    private interface Check{boolean test()throws Exception;}
    private static void until(Check check)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);while(System.nanoTime()<end){if(check.test())return;Thread.sleep(20);}throw new AssertionError("Window path did not follow the unchanged paused transfer's actual session");}
}
