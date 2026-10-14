package dev.ghost.nearbyim;

import android.Manifest;
import android.app.AlertDialog;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcel;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import dev.ghost.nearbyim.core.*;
import dev.ghost.nearbyim.i18n.UiText;
import dev.ghost.nearbyim.transport.Peer;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

/** Native UI classification checks. Loopback sessions do not prove P2P radio support. */
final class AndroidWifiDirectUiChecks {
    private static int checks;

    /** Also runnable on the JVM: the permission policy has no Android runtime side effects. */
    public static void main(String[] args) throws Exception {
        checks=0; permissionMatrix(); permissionRequests();
        System.out.println("Wi-Fi Direct UI permission policy: "+checks+" checks passed");
    }
    private static void permissionMatrix() throws Exception {
        Method policy;
        try { policy=MainActivity.class.getDeclaredMethod("requiredPermissionsFor",int.class,int.class,int.class); }
        catch(NoSuchMethodException missing) { throw new AssertionError("Permission policy must support the actual SDK without mutating Android Build",missing); }
        policy.setAccessible(true);
        for(int sdk:new int[]{26,31,32,33,34}) for(int action:new int[]{1,2,4,6}) {
            List<String> expected=sdk>=33?List.of(Manifest.permission.NEARBY_WIFI_DEVICES):sdk>=31?List.of(Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION):List.of(Manifest.permission.ACCESS_FINE_LOCATION);
            check(permissions(policy,sdk,Peer.WIFI_DIRECT,action).equals(expected),"Direct action "+action+" on Android "+sdk+" must request "+expected);
            check(permissions(policy,sdk,Peer.LAN,action).isEmpty(),"LAN does not acquire a Direct runtime permission");
        }
        check(permissions(policy,26,Peer.BLUETOOTH,2).equals(List.of(Manifest.permission.ACCESS_FINE_LOCATION)),"Legacy Bluetooth search still requests location");
        check(permissions(policy,31,Peer.BLUETOOTH,1).equals(List.of(Manifest.permission.BLUETOOTH_CONNECT)),"Bluetooth reception still requests Connect only");
        check(permissions(policy,34,Peer.BLUETOOTH,2).equals(List.of(Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.BLUETOOTH_SCAN)),"Bluetooth search preserves Scan");
        check(permissions(policy,34,Peer.BLUETOOTH,3).equals(List.of(Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.BLUETOOTH_ADVERTISE)),"Bluetooth discoverability preserves Advertise");
    }
    @SuppressWarnings("unchecked") private static List<String> permissions(Method policy,int sdk,int mode,int action)throws Exception {
        return (List<String>)policy.invoke(null,sdk,mode,action);
    }
    private static void permissionRequests()throws Exception {
        Method missing;
        try {missing=MainActivity.class.getDeclaredMethod("missingPermissionsFor",int.class,int.class,int.class,Set.class);}
        catch(NoSuchMethodException error){throw new AssertionError("Actual permission requests must retain both Android 12 location permissions after approximate-only approval",error);}
        missing.setAccessible(true);
        List<String> pair=List.of(Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION);
        for(int sdk:new int[]{31,32})for(int action:new int[]{1,2,4,6}) {
            check(requests(missing,sdk,Peer.WIFI_DIRECT,action,Set.of()).equals(pair),"Android 12 first Direct request includes both location permissions");
            check(requests(missing,sdk,Peer.WIFI_DIRECT,action,Set.of(Manifest.permission.ACCESS_COARSE_LOCATION)).equals(pair),"Approximate-only approval must still request the full Coarse+Fine pair");
            check(requests(missing,sdk,Peer.WIFI_DIRECT,action,Set.of(Manifest.permission.ACCESS_FINE_LOCATION)).isEmpty(),"Fine Location approval needs no new Direct permission prompt");
        }
        check(requests(missing,26,Peer.WIFI_DIRECT,2,Set.of()).equals(List.of(Manifest.permission.ACCESS_FINE_LOCATION)),"Legacy Direct requests Fine Location only");
        for(int sdk:new int[]{33,34}) {
            check(requests(missing,sdk,Peer.WIFI_DIRECT,2,Set.of(Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION)).equals(List.of(Manifest.permission.NEARBY_WIFI_DEVICES)),"Modern Direct does not substitute location for Nearby permission");
            check(requests(missing,sdk,Peer.WIFI_DIRECT,2,Set.of(Manifest.permission.NEARBY_WIFI_DEVICES)).isEmpty(),"Granted Nearby permission is not requested again");
        }
        check(requests(missing,32,Peer.LAN,2,Set.of()).isEmpty(),"LAN does not request Direct permissions");
        check(requests(missing,31,Peer.BLUETOOTH,2,Set.of(Manifest.permission.BLUETOOTH_CONNECT)).equals(List.of(Manifest.permission.BLUETOOTH_SCAN)),"Bluetooth still requests only its missing permission");
    }
    @SuppressWarnings("unchecked") private static List<String> requests(Method policy,int sdk,int mode,int action,Set<String> granted)throws Exception {return (List<String>)policy.invoke(null,sdk,mode,action,granted);}

    static int run(Instrumentation instrumentation)throws Exception {
        checks=0;permissionMatrix();permissionRequests();
        Context context=instrumentation.getTargetContext();SharedPreferences prefs=context.getSharedPreferences("ui",Context.MODE_PRIVATE);
        boolean hadMode=prefs.contains("lastMode");int previous=prefs.getInt("lastMode",Peer.LAN);
        prefs.edit().remove("lastMode").commit();MainActivity activity=null;
        try {
            activity=(MainActivity)instrumentation.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            MainActivity current=activity;
            await(instrumentation,()->field(current,"controller")!=null,"UI binds its real controller");
            onMain(instrumentation,()->{
                check(((Button)field(current,"newChatButton")).performClick(),"New chat opens the actual nearby screen");
                Button direct=field(current,"directTab"),lan=field(current,"lanTab"),bluetooth=field(current,"bluetoothTab");
                check((int)field(current,"mode")==Peer.WIFI_DIRECT&&direct.isSelected()&&direct.isShown(),"A fresh install selects visible Wi-Fi Direct");
                ViewGroup tabs=(ViewGroup)direct.getParent();check(tabs.getChildAt(0)==direct&&tabs.getChildAt(1)==lan&&tabs.getChildAt(2)==bluetooth,"Direct, LAN and Bluetooth remain in the intended order");
                for(Button tab:new Button[]{lan,bluetooth,direct}) { check(tab.isEnabled()&&tab.performClick(),"Each transport remains manually selectable");check(tab.isSelected(),"Manual transport selection renders immediately");check(prefs.getInt("lastMode",0)==(int)field(current,"mode"),"Manual transport selection is retained for the next Activity"); }
                Object action=action(2,Peer.WIFI_DIRECT);
                Method actual=MainActivity.class.getDeclaredMethod("requiredPermissions",action.getClass());actual.setAccessible(true);
                check(actual.invoke(current,action).equals(permissions(MainActivity.class.getDeclaredMethod("requiredPermissionsFor",int.class,int.class,int.class),Build.VERSION.SDK_INT,Peer.WIFI_DIRECT,2)),"Actual action requests the current Android SDK permission policy");
                if(Build.VERSION.SDK_INT>=33)check((boolean)invoke(current,"legacyDirectLocationEnabled",new Class<?>[0]),"Modern Direct actions do not require system Location mode");
                ChatController controller=field(current,"controller");
                Peer sample=Peer.wifiDirect(UUID.randomUUID().toString(),"Android peer","02:11:22:33:44:55");
                try {
                    controller.peers.put(sample.key,sample);render(current);View list=field(current,"peersList");
                    check(containsText(list,AndroidText.get(current,"directPeerHint"))&&!containsText(list,sample.wifiDirectAddress),"Direct device rows explain identity checking without exposing a temporary discovery address");
                }finally {controller.peers.remove(sample.key);render(current);}
                if(!controller.directAvailable()) {
                    check(!((Button)field(current,"receiveButton")).isEnabled()&&!((Button)field(current,"searchButton")).isEnabled(),"Unavailable P2P disables Direct actions");
                    check(((TextView)field(current,"bluetoothState")).getText().toString().equals(AndroidText.get(current,"directUnavailable")),"Unavailable P2P has a visible reason");
                    invoke(current,"withPermissions",new Class<?>[]{action.getClass()},action);
                    check(!controller.directRunning&&field(current,"pendingAction")==null,"An unavailable Direct action cannot start a transport or leave a pending permission/settings request");
                    lan.performClick();check(((Button)field(current,"receiveButton")).isEnabled(),"Unavailable Direct leaves LAN reception usable");direct.performClick();
                }
                set(current,"pendingAction",action);set(current,"pendingStage",5);
                Bundle saved=new Bundle();instrumentation.callActivityOnSaveInstanceState(current,saved);
                Parcel parcel=Parcel.obtain();try {
                    parcel.writeBundle(saved);parcel.setDataPosition(0);Bundle restored=parcel.readBundle(MainActivity.class.getClassLoader());
                    Object restoredAction=restored.getSerializable("pendingAction");
                    check(restored.getInt("pendingStage")==5&&(int)field(restoredAction,"mode")==Peer.WIFI_DIRECT&&(int)field(restoredAction,"type")==2,"Wi-Fi settings preserves the pending mode and search action across Activity state serialization");
                }finally { parcel.recycle();set(current,"pendingAction",null);set(current,"pendingStage",0); }
                testWifiSettingsRebind(current,controller,action,instrumentation);
                AtomicInteger successorChanges=new AtomicInteger();Runnable successor=()->successorChanges.incrementAndGet();
                controller.observe(successor);successorChanges.set(0);
                try {
                    // Exercise the old Activity's real stop path after a successor subscribes.
                    instrumentation.callActivityOnStop(current);
                    check(field(controller,"observer")==successor,"Stopping an older Activity preserves its successor's observer");
                    controller.stopSearch(Peer.LAN);
                    check(successorChanges.get()>0,"Controller changes still notify the successor after an old Activity stops");
                }finally {controller.observe(null);}
                return null;
            });
        }finally {
            if(activity!=null){MainActivity current=activity;onMain(instrumentation,()->{current.finish();return null;});instrumentation.waitForIdleSync();}
            if(hadMode)prefs.edit().putInt("lastMode",previous).commit();else prefs.edit().remove("lastMode").commit();
        }
        return checks;
    }
    private static void testWifiSettingsRebind(MainActivity activity,ChatController controller,Object action,Instrumentation instrumentation)throws Exception {
        try {
            set(activity,"pendingAction",action);set(activity,"pendingStage",5);set(activity,"controller",null);
            invoke(activity,"onActivityResult",new Class<?>[]{int.class,int.class,Intent.class},10,android.app.Activity.RESULT_CANCELED,null);
            check(field(activity,"pendingAction")==action&&(int)field(activity,"pendingStage")==5,"Wi-Fi Settings return before service binding preserves the exact pending Direct action");
            invoke(activity,"resumePending",new Class<?>[0]);
            check(field(activity,"pendingAction")==action,"Unbound Activity cannot mistake missing controller for Wi-Fi off");
            Bundle saved=new Bundle();instrumentation.callActivityOnSaveInstanceState(activity,saved);
            check(saved.getBoolean("wifiSettingsReturned"),"Deferred Wi-Fi Settings result survives Activity recreation");
            set(activity,"controller",controller);
            // Unavailable/off emulators exercise the real rebind resolution without starting a radio.
            if(!controller.directEnabled()||!controller.directAvailable()) {
                invoke(activity,"resumePending",new Class<?>[0]);
                check(field(activity,"pendingAction")==null&&(int)field(activity,"pendingStage")==0,"Rebound controller resolves the returned Settings action rather than leaving it indefinitely pending");
                check(!(boolean)field(activity,"wifiSettingsReturned"),"A completed Settings result cannot affect the next action");
            }
        }finally {set(activity,"controller",controller);set(activity,"pendingAction",null);set(activity,"pendingStage",0);try{set(activity,"wifiSettingsReturned",false);}catch(NoSuchFieldException absent){}render(activity);}
    }

    /** Uses an actual approved LAN/Noise channel, temporarily classifying its UI as Direct. */
    static int run(Instrumentation instrumentation,MainActivity activity)throws Exception {
        checks=0;ChatController controller=onMain(instrumentation,()->field(activity,"controller"));
        check(controller!=null&&controller.connected&&controller.hasSession(),"UI path classification requires an actual approved session");
        int savedMode=controller.sessionMode;boolean savedConnected=controller.connected,savedConnecting=controller.connecting;
        UiText savedNotice=controller.routeNotice,savedStatus=controller.status,savedInfo=controller.directInfo;
        boolean savedRunning=controller.directRunning,savedListening=controller.directListening,savedSearching=controller.directSearching;
        int uiMode=onMain(instrumentation,()->field(activity,"mode"));boolean outgoing=onMain(instrumentation,()->field(activity,"outgoingRequest"));
        int savedPage=onMain(instrumentation,()->field(activity,"page"));boolean savedDetail=onMain(instrumentation,()->field(activity,"detail"));
        List<ChatStore.Message> savedMessages=controller.messages;String peer=controller.selectedId;
        AttachmentInfo info=AttachmentInfo.v2(UUID.randomUUID().toString(),"direct-ui.bin","application/octet-stream",1024,"0".repeat(64),1);
        try {
            onMain(instrumentation,()->{
                set(activity,"mode",Peer.WIFI_DIRECT);set(activity,"outgoingRequest",false);set(activity,"detail",false);set(activity,"page",1);
                controller.directRunning=true;controller.directListening=false;controller.directSearching=true;controller.directInfo=UiText.of("directAdvertising");
                render(activity);TextView connection=field(activity,"connectionInfo");check(connection.isShown()&&connection.getText().toString().equals(AndroidText.get(activity,"directAdvertising")),"Direct advertising is visible without a TCP readiness claim");
                check(((TextView)field(activity,"receiveStatus")).getText().toString().equals(AndroidText.get(activity,"directAdvertising")),"Advertising does not claim an established listening socket");
                check(((Button)field(activity,"searchButton")).getText().toString().equals(AndroidText.get(activity,"stopSearch")),"Direct search state does not depend on Bluetooth");
                controller.directListening=true;controller.directInfo=UiText.of("directListening","127.0.0.1:39741");render(activity);
                check(((TextView)field(activity,"connectionInfo")).getText().toString().contains("127.0.0.1:39741"),"Direct connection info shows the actual bound endpoint");
                controller.sessionMode=Peer.WIFI_DIRECT;controller.routeNotice=UiText.EMPTY;set(activity,"detail",true);set(activity,"page",0);
                for(String state:new String[]{"transferring","queued","paused","checking"}) {
                    AttachmentRecord record=new AttachmentRecord(info,true,state,512);
                    controller.messages=List.of(new ChatStore.Message(info.id,info.name,"pending",true,2,record));render(activity);
                    TextView path=activity.findViewById(android.R.id.content).findViewWithTag("attachmentPath");
                    String key=state.equals("queued")?"attachmentPathPendingWifiDirect":"attachmentPathWifiDirect";
                    check(path!=null&&path.isShown()&&path.getText().toString().equals(AndroidText.get(activity,key)),"Actual approved mode determines Direct file card for "+state);
                }
                check(((TextView)field(activity,"networkName")).getText().toString().equals(AndroidText.get(activity,"wifiDirect")),"Main header names the approved Direct path");
                check(((TextView)field(activity,"chatStatus")).getText().toString().contains(AndroidText.get(activity,"wifiDirect")),"Chat header names Direct independently of encryption/identity verification");
                // A real GC retains the advertisement ACK; only GO emits a socket-listening callback.
                controller.directListening=true;controller.directInfo=UiText.of("directAdvertising");render(activity);
                check(((TextView)field(activity,"connectionInfo")).getText().toString().equals(AndroidText.get(activity,"pathDetailsWifiDirect")),"An approved group-client channel does not retain an advertising-only waiting claim");
                AlertDialog direct=(AlertDialog)invoke(activity,"showTransferPath",new Class<?>[]{String.class},peer);
                try {check(((TextView)direct.findViewById(android.R.id.message)).getText().toString().equals(AndroidText.get(activity,"pathDetailsWifiDirect")),"Connected Direct details explain the actual path");}finally {direct.dismiss();}
                controller.sessionMode=Peer.LAN;controller.routeNotice=UiText.of("wifiDirectFallbackLan",UiText.of("directConnectTimeout"));render(activity);
                check(((TextView)field(activity,"chatStatus")).getText().toString().contains(AndroidText.get(activity,"lanPathUnconfirmed")),"LAN fallback does not claim Direct or confirmed topology");
                AlertDialog fallback=(AlertDialog)invoke(activity,"showTransferPath",new Class<?>[]{String.class},peer);
                try {String message=((TextView)fallback.findViewById(android.R.id.message)).getText().toString();check(message.contains(AndroidText.get(activity,"pathDetailsLan"))&&message.contains(AndroidText.get(activity,controller.routeNotice)),"Connected fallback details retain the Direct failure reason");}finally {fallback.dismiss();}
                controller.sessionMode=Peer.BLUETOOTH;controller.routeNotice=UiText.of("wifiDirectFallbackBluetooth",UiText.of("directConnectTimeout"));render(activity);
                check(((TextView)field(activity,"networkName")).getText().toString().equals(AndroidText.get(activity,"bluetooth")),"Bluetooth fallback keeps its actual main-header classification");
                check(((TextView)activity.findViewById(android.R.id.content).findViewWithTag("attachmentPath")).getText().toString().equals(AndroidText.get(activity,"attachmentPathBluetooth")),"Bluetooth fallback keeps its actual file-card classification");
                AlertDialog bluetooth=(AlertDialog)invoke(activity,"showTransferPath",new Class<?>[]{String.class},peer);
                try {String message=((TextView)bluetooth.findViewById(android.R.id.message)).getText().toString();check(message.contains(AndroidText.get(activity,"pathDetailsBluetooth"))&&message.contains(AndroidText.get(activity,controller.routeNotice)),"Bluetooth path details also retain the fallback reason");}finally {bluetooth.dismiss();}
                controller.connected=false;controller.connecting=true;controller.status=UiText.of("handshake");set(activity,"outgoingRequest",true);render(activity);
                check(((TextView)field(activity,"requestStatus")).getText().toString().contains(AndroidText.get(activity,controller.routeNotice)),"Fallback reason remains visible during handshake");
                TextView waiting=field(activity,"reconnectHint");check(waiting.isShown()&&waiting.getText().toString().contains(AndroidText.get(activity,controller.routeNotice)),"The visible chat waiting state retains the fallback reason");
                check(((TextView)field(activity,"networkName")).getVisibility()==View.GONE,"An unapproved channel does not claim a connected Direct header");
                return null;
            });
        }finally {
            onMain(instrumentation,()->{controller.sessionMode=savedMode;controller.connected=savedConnected;controller.connecting=savedConnecting;controller.routeNotice=savedNotice;controller.status=savedStatus;controller.messages=savedMessages;controller.directRunning=savedRunning;controller.directListening=savedListening;controller.directSearching=savedSearching;controller.directInfo=savedInfo;set(activity,"mode",uiMode);set(activity,"outgoingRequest",outgoing);set(activity,"detail",savedDetail);set(activity,"page",savedPage);render(activity);return null;});
        }
        return checks;
    }
    private static Object action(int type,int mode)throws Exception {Class<?> action=Class.forName(MainActivity.class.getName()+"$UiAction");Constructor<?> constructor=action.getDeclaredConstructor(int.class,int.class);constructor.setAccessible(true);return constructor.newInstance(type,mode);}
    private static void render(MainActivity activity)throws Exception {invoke(activity,"render",new Class<?>[0]);}
    private static boolean containsText(View view,String value) {if(view instanceof TextView&&((TextView)view).getText().toString().contains(value))return true;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)if(containsText(group.getChildAt(i),value))return true;}return false;}
    private static Object invoke(Object target,String name,Class<?>[] types,Object...args)throws Exception {Method method=target.getClass().getDeclaredMethod(name,types);method.setAccessible(true);return method.invoke(target,args);}
    @SuppressWarnings("unchecked") private static <T>T field(Object target,String name)throws Exception {Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return (T)field.get(target);}
    private static void set(Object target,String name,Object value)throws Exception {Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);field.set(target,value);}
    private static <T>T onMain(Instrumentation instrumentation,Callable<T> operation)throws Exception {Object[] result=new Object[2];instrumentation.runOnMainSync(()->{try{result[0]=operation.call();}catch(Throwable error){result[1]=error;}});if(result[1]!=null)throw new Exception((Throwable)result[1]);@SuppressWarnings("unchecked")T value=(T)result[0];return value;}
    private static void await(Instrumentation instrumentation,Callable<Boolean> predicate,String message)throws Exception {long end=System.currentTimeMillis()+10000;while(System.currentTimeMillis()<end){if(onMain(instrumentation,predicate)){checks++;return;}Thread.sleep(50);}throw new AssertionError(message);}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
}
