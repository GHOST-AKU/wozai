package dev.ghost.wozai;

import dev.ghost.nearbyim.core.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import javax.swing.*;

/** Deterministically interleave real viewport layout with consecutive photo history updates. */
public final class ThumbnailScrollTests {
    private static Object field(Object value,String name)throws Exception {Field field=value.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(value);}
    private static void set(Object value,String name,Object replacement)throws Exception {Field field=value.getClass().getDeclaredField(name);field.setAccessible(true);field.set(value,replacement);}
    private static void edt(Runnable action)throws Exception {SwingUtilities.invokeAndWait(action);}
    private static DesktopStore.Message photo(AttachmentInfo info,String state)throws Exception {return new DesktopStore.Message(info.id,info.name,System.currentTimeMillis(),false,"received",info.time,new AttachmentRecord(info,false,state,state.equals("received")?info.size:0));}
    public static void main(String[] args)throws Exception {
        Path root=Files.createTempDirectory("thumbnail-scroll-");DesktopWindow[] window={null};DesktopClient[] client={null};String peer=UUID.randomUUID().toString();
        try(DesktopStore store=new DesktopStore(root)) {
            store.setSetting("language","en");store.peer(new DesktopStore.Peer(peer,"Phone","",""));var identity=DesktopIdentity.load(root.resolve("identity.properties"));
            List<DesktopStore.Message> history=new ArrayList<>();for(int i=0;i<35;i++)history.add(new DesktopStore.Message(UUID.randomUUID().toString(),"Earlier chat message "+i,1_700_000_000_000L+i*60_000L,false,"received"));
            edt(()->{try{AppTheme.install(false);window[0]=new DesktopWindow(store,identity,root);window[0].setVisible(true);set(window[0],"shuttingDown",true);set(window[0],"selected",peer);client[0]=(DesktopClient)field(window[0],"client");}catch(Exception error){throw new RuntimeException(error);}});
            MessagePane pane=(MessagePane)field(window[0],"transcript");JScrollPane scroll=(JScrollPane)SwingUtilities.getAncestorOfClass(JScrollPane.class,pane);Method render=DesktopWindow.class.getDeclaredMethod("renderTranscript",String.class,List.class);render.setAccessible(true);
            edt(()->{try{render.invoke(window[0],peer,List.copyOf(history));window[0].validate();scroll.getVerticalScrollBar().setValue(scroll.getVerticalScrollBar().getMaximum());}catch(Exception error){throw new RuntimeException(error);}});edt(()->{});
            AttachmentInfo first=AttachmentInfo.v2(UUID.randomUUID().toString(),"first.png","image/png",100,"0".repeat(64),1);DesktopStore.Message offered=photo(first,"offered"),received=photo(first,"received");
            edt(()->{try{
                var messages=new ArrayList<>(history);messages.add(offered);render.invoke(window[0],peer,messages);
                // Swing may lay out the first history update before its deferred scroll callback runs.
                scroll.validate();messages.set(messages.size()-1,received);render.invoke(window[0],peer,messages);scroll.validate();
                pane.thumbnail(first.id,false,new BufferedImage(800,600,BufferedImage.TYPE_INT_RGB));
            }catch(Exception error){throw new RuntimeException(error);}});edt(()->{});edt(()->{});
            edt(()->{scroll.validate();JScrollBar bar=scroll.getVerticalScrollBar();if(bar.getValue()+bar.getVisibleAmount()<bar.getMaximum()-24)throw new AssertionError("Interleaved photo layout lost bottom anchor: "+bar.getValue()+" + "+bar.getVisibleAmount()+" < "+bar.getMaximum());});
            history.add(received);AttachmentInfo second=AttachmentInfo.v2(UUID.randomUUID().toString(),"second.png","image/png",100,"0".repeat(64),2);DesktopStore.Message nextOffered=photo(second,"offered"),nextReceived=photo(second,"received");
            edt(()->{try{scroll.getVerticalScrollBar().setValue(180);var messages=new ArrayList<>(history);messages.add(nextOffered);render.invoke(window[0],peer,messages);scroll.validate();messages.set(messages.size()-1,nextReceived);render.invoke(window[0],peer,messages);scroll.validate();pane.thumbnail(second.id,false,new BufferedImage(800,600,BufferedImage.TYPE_INT_RGB));}catch(Exception error){throw new RuntimeException(error);}});edt(()->{});edt(()->{});
            edt(()->{if(Math.abs(scroll.getVerticalScrollBar().getValue()-180)>1)throw new AssertionError("Photo update moved a reader browsing older messages");});
            System.out.println("ThumbnailScrollTests: interleaved photo layout keeps bottom anchor and older-message position");
        }finally{
            if(window[0]!=null)edt(()->{try{((javax.swing.Timer)field(window[0],"draftTimer")).stop();((javax.swing.Timer)field(window[0],"localeTimer")).stop();window[0].dispose();}catch(Exception error){throw new RuntimeException(error);}});
            if(client[0]!=null)client[0].close();try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}
        }
    }
}
