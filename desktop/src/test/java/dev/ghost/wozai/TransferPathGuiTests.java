package dev.ghost.wozai;

import dev.ghost.nearbyim.core.*;
import javax.swing.*;
import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

public final class TransferPathGuiTests {
    public static void main(String[] args)throws Exception {
        java.lang.reflect.Method bind;
        try{bind=MessagePane.class.getDeclaredMethod("path",Function.class);}
        catch(NoSuchMethodException missing){throw new AssertionError("Desktop transfer cards have no visible path binding",missing);}
        AttachmentInfo info=AttachmentInfo.v2(UUID.randomUUID().toString(),"path.bin","application/octet-stream",1024,"0".repeat(64),1);
        var text=new DesktopStore.Message(UUID.randomUUID().toString(),"unrelated text",1,false,"",1,null);
        var active=new DesktopStore.Message(info.id,info.name,2,true,"pending",2,new AttachmentRecord(info,true,"transferring",512));
        var paused=new DesktopStore.Message(info.id,info.name,2,true,"unknown",2,new AttachmentRecord(info,true,"paused",512));
        SwingUtilities.invokeAndWait(()->{
            try {
                AppTheme.install(false);Strings strings=new Strings("en");MessagePane pane=new MessagePane();pane.setSize(600,600);
                AtomicReference<TransferPath> path=new AtomicReference<>(TransferPath.LAN_UNCONFIRMED);
                bind.invoke(pane,(Function<DesktopStore.Message,TransferPath>)message->message.attachment()==null?TransferPath.NONE:path.get());
                List<DesktopStore.Message> messages=List.of(text,active);pane.render(messages,strings);
                JLabel label=pathLabel(pane);check(label.getText().equals(strings.text("attachmentPathLanUnconfirmed")),"Active LAN path invisible or claimed as direct");
                Component unrelated=components(pane).stream().filter(c->c instanceof JTextArea t&&t.getText().equals("unrelated text")).findFirst().orElseThrow();
                path.set(TransferPath.BLUETOOTH);pane.render(messages,strings);check(pathLabel(pane).getText().equals(strings.text("attachmentPathBluetooth")),"Unchanged history hides a changed live channel");
                check(components(pane).contains(unrelated),"Path feedback rebuilt an unrelated message row");
                path.set(TransferPath.DISCONNECTED);pane.render(List.of(text,paused),strings);check(pathLabel(pane).getText().equals(strings.text("attachmentPathDisconnected")),"Paused disconnect retains a stale active channel");
                path.set(TransferPath.LAN_UNCONFIRMED);pane.render(List.of(text,paused),strings);check(pathLabel(pane).getText().equals(strings.text("attachmentPathLanUnconfirmed")),"Paused history ignores a reconnected current channel");
                check(pathLabel(pane).getAccessibleContext().getAccessibleName().contains(strings.text("attachmentPathLanUnconfirmed")),"Transfer path lacks a reader label");
                var terminal=new DesktopStore.Message(info.id,info.name,2,true,"delivered",2,new AttachmentRecord(info,true,"delivered",info.size).withRoute("wifi-direct"));
                path.set(TransferPath.WIFI_DIRECT);pane.render(List.of(text,terminal),strings);
                check(pathLabel(pane).isVisible()&&pathLabel(pane).getText().equals(strings.text("attachmentPathWifiDirect")),"Completed file hides its actual route");
                System.out.println("TransferPathGuiTests: 7 Swing checks passed (path changes, terminal route, unrelated-row reuse, accessibility)");
            }catch(Exception e){throw new AssertionError(e);}
        });
    }
    private static JLabel pathLabel(Component root){return components(root).stream().filter(c->c instanceof JLabel&&"attachmentPath".equals(c.getName())).map(c->(JLabel)c).findFirst().orElseThrow(()->new AssertionError("Transfer card path is absent"));}
    private static List<Component> components(Component root){List<Component> result=new ArrayList<>();result.add(root);if(root instanceof Container parent)for(Component child:parent.getComponents())result.addAll(components(child));return result;}
    private static void check(boolean pass,String message){if(!pass)throw new AssertionError(message);}
}
