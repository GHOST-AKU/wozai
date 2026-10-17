package dev.ghost.nearbyim;

import android.app.Instrumentation;
import android.content.Intent;
import android.view.View;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;
import dev.ghost.nearbyim.core.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.Callable;

final class AndroidTransferPathChecks {
    private static int checks;
    public static void main(String[] args)throws Exception {
        checks=0;routeAwareReuse();System.out.println("Android attachment row reuse: "+checks+" checks passed");
    }
    private static void routeAwareReuse()throws Exception {
        Method reusable;try{reusable=MainActivity.class.getDeclaredMethod("sameActiveAttachmentMessage",ChatStore.Message.class,ChatStore.Message.class);}
        catch(NoSuchMethodException missing){throw new AssertionError("Active row reuse has no route-aware predicate",missing);}reusable.setAccessible(true);
        AttachmentInfo info=AttachmentInfo.v2(UUID.randomUUID().toString(),"route.bin","application/octet-stream",1024,"0".repeat(64),1);
        ChatStore.Message before=message(info,512,"bluetooth"),progress=message(info,768,"bluetooth"),changed=message(info,768,"lan");
        check((boolean)reusable.invoke(null,before,progress),"Progress-only updates should retain the active row");
        check(!(boolean)reusable.invoke(null,before,changed),"Same-state route changes must refresh the route detail callback");
    }
    private static ChatStore.Message message(AttachmentInfo info,long bytes,String route)throws Exception {return new ChatStore.Message(info.id,info.name,"pending",true,2,new AttachmentRecord(info,true,"transferring",bytes).withRoute(route));}
    static int run(Instrumentation instrumentation,MainActivity activity)throws Exception {
        checks=0;routeAwareReuse();
        ChatController controller=onMain(instrumentation,()->field(activity,"controller"));
        check(controller!=null&&controller.connected,"Path checks require the actual approved native session");
        String peer=controller.selectedId;List<ChatStore.Message> saved=controller.messages;boolean connected=controller.connected;
        AttachmentInfo info=AttachmentInfo.v2(UUID.randomUUID().toString(),"route.bin","application/octet-stream",1024,"0".repeat(64),1);
        ChatStore.Message text=new ChatStore.Message(UUID.randomUUID().toString(),"path check text","",false,1);
        try {
            onMain(instrumentation,()->{controller.messages=List.of(text,message(info,512,"bluetooth"));render(activity);return null;});
            onMain(instrumentation,()->{controller.messages=List.of(text,message(info,768,"lan"));render(activity);return null;});
            TextView changed=onMain(instrumentation,()->activity.findViewById(android.R.id.content).findViewWithTag("attachmentPath"));
            check(onMain(instrumentation,()->changed.getText().toString().equals(AndroidText.get(activity,"attachmentPathLanUnconfirmed"))),"Same-state route update leaves a stale visible label");
            check(onMain(instrumentation,changed::performClick),"Latest attachment path action is not clickable");
            instrumentation.waitForIdleSync();
            try {
                check(dialogShows(instrumentation,AndroidText.get(activity,"pathDetailsLan")),"Same-state LAN file route opens stale Bluetooth details");
            }finally {instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);instrumentation.waitForIdleSync();}
            for(String state:new String[]{"transferring","queued","paused","checking"}) {
                AttachmentRecord record=new AttachmentRecord(info,true,state,512).withRoute("bluetooth");
                onMain(instrumentation,()->{controller.messages=List.of(text,new ChatStore.Message(info.id,info.name,"pending",true,2,record));render(activity);return null;});
                TextView label=onMain(instrumentation,()->activity.findViewById(android.R.id.content).findViewWithTag("attachmentPath"));
                check(label!=null&&label.getVisibility()==View.VISIBLE,"File card does not show a path for "+state);
                check(onMain(instrumentation,()->label.getText().toString().equals(AndroidText.get(activity,controller.attachmentRoute(peer,record).key()))),"Path differs from the file record");
                check(onMain(instrumentation,()->label.isFocusable()&&label.hasOnClickListeners()),"Path details are inaccessible");
            }
            onMain(instrumentation,()->{controller.connected=false;render(activity);return null;});
            check(onMain(instrumentation,()->((TextView)activity.findViewById(android.R.id.content).findViewWithTag("attachmentPath")).getText().toString().equals(AndroidText.get(activity,"attachmentPathBluetooth"))),"Chat disconnection rewrites file route");
            onMain(instrumentation,()->{controller.connected=true;render(activity);return null;});
            check(onMain(instrumentation,()->((TextView)activity.findViewById(android.R.id.content).findViewWithTag("attachmentPath")).getText().toString().equals(AndroidText.get(activity,"attachmentPathBluetooth"))),"Chat reconnection rewrites file route");
            AttachmentRecord received=new AttachmentRecord(info,false,"received",info.size).withRoute("wifi-direct");
            onMain(instrumentation,()->{controller.messages=List.of(new ChatStore.Message(info.id,info.name,"",false,2,received));render(activity);return null;});
            check(onMain(instrumentation,()->((TextView)activity.findViewById(android.R.id.content).findViewWithTag("attachmentPath")).getText().toString().equals(AndroidText.get(activity,"attachmentPathWifiDirect"))),"Completed native bubble loses its actual file route");
            View location=onMain(instrumentation,()->activity.findViewById(android.R.id.content).findViewWithTag("attachmentLocation"));
            check(location!=null&&location.hasOnClickListeners(),"Completed received file lacks a visible saved-location action");
            int before=controller.messages.size();
            onMain(instrumentation,()->{Method result=MainActivity.class.getDeclaredMethod("onActivityResult",int.class,int.class,Intent.class);result.setAccessible(true);result.invoke(activity,25,android.app.Activity.RESULT_OK,new Intent().setData(android.net.Uri.parse("content://dev.ghost.nearbyim.test.sources/export/location")));return null;});
            check(controller.messages.size()==before&&field(activity,"pendingAttachmentPeer")==null,"Browsing a saved location created a send selection");
        }finally {onMain(instrumentation,()->{controller.connected=connected;controller.messages=saved;render(activity);return null;});}
        return checks;
    }
    private static boolean dialogShows(Instrumentation instrumentation,String text)throws Exception {
        long end=System.currentTimeMillis()+5000;
        do {AccessibilityNodeInfo root=instrumentation.getUiAutomation().getRootInActiveWindow();if(root!=null&&!root.findAccessibilityNodeInfosByText(text).isEmpty())return true;Thread.sleep(50);}while(System.currentTimeMillis()<end);
        return false;
    }
    private static void render(MainActivity activity)throws Exception{Method method=MainActivity.class.getDeclaredMethod("renderChat");method.setAccessible(true);method.invoke(activity);}
    @SuppressWarnings("unchecked") private static <T>T field(Object target,String name)throws Exception{Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return (T)field.get(target);}
    private static <T>T onMain(Instrumentation instrumentation,Callable<T> operation)throws Exception{Object[] result=new Object[2];instrumentation.runOnMainSync(()->{try{result[0]=operation.call();}catch(Throwable error){result[1]=error;}});if(result[1]!=null)throw new Exception((Throwable)result[1]);@SuppressWarnings("unchecked")T value=(T)result[0];return value;}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
}
