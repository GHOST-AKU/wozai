package dev.ghost.wozai;
import dev.ghost.nearbyim.core.*;
import javax.swing.*;
import java.awt.*;
import java.util.*;
public final class AttachmentGuiTests {
    static boolean button(Component c,String text){if(c instanceof JButton&&((JButton)c).getText().equals(text))return true;if(c instanceof Container)for(Component child:((Container)c).getComponents())if(button(child,text))return true;return false;}
    public static void main(String[] args)throws Exception {
        AttachmentInfo info=new AttachmentInfo(AttachmentStoreTests.ID,"photo.jpg","image/jpeg",100,"0".repeat(64),1);
        SwingUtilities.invokeAndWait(()->{
            try {
                AppTheme.install(false);MessagePane pane=new MessagePane();pane.setSize(600,500);Strings strings=new Strings("en");
                AttachmentRecord offer=new AttachmentRecord(info,false,"offered",0);
                pane.render(java.util.List.of(new DesktopStore.Message(info.id,info.name,1,false,"received",1,offer)),strings);
                if(!button(pane,"Accept")||!button(pane,"Reject"))throw new AssertionError("Receive consent controls missing");
                AttachmentRecord done=new AttachmentRecord(info,false,"received",100);
                pane.render(java.util.List.of(new DesktopStore.Message(info.id,info.name,1,false,"received",1,done)),strings);
                if(!button(pane,"Open")||!button(pane,"Save as"))throw new AssertionError("Received file actions missing");
            }catch(Exception e){throw new RuntimeException(e);}
        });
        System.out.println("AttachmentGuiTests passed");
    }
}
