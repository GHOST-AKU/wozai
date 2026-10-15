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
        checks=0; permissionMatrix(); permissionRequests(); chatModeNormalization();
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
        checks=0;permissionMatrix();permissionRequests();chatModeNormalization();fileDiscoveryFailure(instrumentation);
        Context context=instrumentation.getTargetContext();SharedPreferences prefs=context.getSharedPreferences("ui",Context.MODE_PRIVATE);
        boolean hadMode=prefs.contains("lastMode");int previous=prefs.getInt("lastMode",Peer.LAN);
        prefs.edit().putInt("lastMode",Peer.WIFI_DIRECT).commit();MainActivity activity=null;
        try {
            activity=(MainActivity)instrumentation.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));MainActivity current=activity;
            await(instrumentation,()->field(current,"controller")!=null,"UI binds its real controller");
            onMain(instrumentation,()->{
                ((Button)field(current,"newChatButton")).performClick();Button lan=field(current,"lanTab"),bluetooth=field(current,"bluetoothTab");
                ViewGroup tabs=(ViewGroup)lan.getParent();check(tabs.getChildCount()==2&&tabs.getChildAt(0)==lan&&tabs.getChildAt(1)==bluetooth,"Chat exposes exactly LAN and Bluetooth tabs");
                check((int)field(current,"mode")==Peer.LAN&&lan.isSelected()&&prefs.getInt("lastMode",0)==Peer.LAN,"Stored Direct chat preference normalizes durably to LAN");
                for(Button tab:new Button[]{bluetooth,lan}){check(tab.performClick()&&tab.isSelected(),"Both chat modes remain selectable");check(prefs.getInt("lastMode",0)==(int)field(current,"mode"),"Chat mode selection persists");}
                ChatController controller=field(current,"controller");AtomicInteger successorChanges=new AtomicInteger();Runnable successor=successorChanges::incrementAndGet;
                controller.observe(successor);successorChanges.set(0);
                try{instrumentation.callActivityOnStop(current);check(field(controller,"observer")==successor,"Older Activity stop preserves the successor observer");controller.observe(successor);check(successorChanges.get()>0,"Successor observer still receives updates");}
                finally{invoke(controller,"unobserve",new Class<?>[]{Runnable.class},successor);}
                return null;
            });
        }finally{if(activity!=null){MainActivity current=activity;onMain(instrumentation,()->{current.finish();return null;});}SharedPreferences.Editor edit=prefs.edit();if(hadMode)edit.putInt("lastMode",previous);else edit.remove("lastMode");edit.commit();}
        return checks;
    }
    private static void fileDiscoveryFailure(Instrumentation instrumentation)throws Exception {
        onMain(instrumentation,()->{
            Method request=dev.ghost.nearbyim.transport.WifiDirectTransport.class.getDeclaredMethod("discoveryRequest");request.setAccessible(true);
            Object query=request.invoke(null);
            check(query.equals(android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest.newInstance()),"Request must obtain PTR and TXT records, not PTR only");
            check(!query.equals(android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest.newInstance("_wozai-p2p._tcp")),"PTR-only request cannot satisfy the registry TXT requirement");
            String peer=UUID.randomUUID().toString();
            for(String stage:List.of("error","stop","discovery","found")){
                var platform=new dev.ghost.nearbyim.transport.AndroidFilePlatform(instrumentation.getTargetContext(),UUID.randomUUID().toString(),"Phone",peer);
                try{
                    AtomicInteger failures=new AtomicInteger();Field failed=platform.getClass().getDeclaredField("directFailed");failed.setAccessible(true);failed.set(platform,(java.util.function.Consumer<Throwable>)error->failures.incrementAndGet());
                    Field opened=platform.getClass().getDeclaredField("directOpened");opened.setAccessible(true);opened.set(platform,(java.util.function.Consumer<AutomaticFileRoutes.Opened>)stream->{});
                    Object direct=field(platform,"direct");dev.ghost.nearbyim.transport.TransportListener listener=field(direct,"listener");
                    if(stage.equals("stop"))listener.onSearchStopFailed(Peer.WIFI_DIRECT,UiText.of("directSearchStopFailed"));
                    else if(stage.equals("error"))listener.onError(Peer.WIFI_DIRECT,UiText.of("directDiscoveryFailed"),false);
                    else{
                        listener.onListening(Peer.WIFI_DIRECT,UiText.of("directAdvertising"));
                        Runnable deadline=field(platform,"discoveryTimeout");check(deadline!=null,"Advertising arms a distinct peer discovery deadline");
                        listener.onPeer(Peer.wifiDirect(UUID.randomUUID().toString(),"Other","02:11:22:33:44:55"));
                        check(field(platform,"discoveryTimeout")==deadline,"An unrelated peer must not extend discovery");
                        if(stage.equals("found")){listener.onPeer(Peer.wifiDirect(peer,"Peer","02:11:22:33:44:66"));check(field(platform,"discoveryTimeout")==null,"Expected peer moves to the full group connection budget");}
                        else deadline.run();
                    }
                    check(failures.get()==(stage.equals("found")?0:1),"Search failure must resolve FILE promptly; discovered peer retains connection budget");
                }finally{platform.close();}
            }return null;
        });
    }
    private static void chatModeNormalization()throws Exception {
        Method normalize;try{normalize=MainActivity.class.getDeclaredMethod("normalizeChatMode",int.class);}catch(NoSuchMethodException missing){throw new AssertionError("Direct must no longer be restored as a chat mode",missing);}normalize.setAccessible(true);
        for(int mode:new int[]{0,Peer.LAN,Peer.BLUETOOTH,Peer.WIFI_DIRECT,99})check((int)normalize.invoke(null,mode)==(mode==Peer.BLUETOOTH?Peer.BLUETOOTH:Peer.LAN),"Chat restoration accepts only LAN or Bluetooth");
        Method file;try{file=MainActivity.class.getDeclaredMethod("fileRoutePermissionsFor",int.class,Set.class);}catch(NoSuchMethodException missing){throw new AssertionError("File routing needs its own optional Direct permission policy",missing);}file.setAccessible(true);
        for(int sdk:new int[]{26,31,32,33,34})check(file.invoke(null,sdk,Set.of()).equals(requests(MainActivity.class.getDeclaredMethod("missingPermissionsFor",int.class,int.class,int.class,Set.class),sdk,Peer.WIFI_DIRECT,1,Set.of())),"File route permissions do not depend on chat mode");
    }
    static int run(Instrumentation instrumentation,MainActivity activity)throws Exception {checks=0;chatModeNormalization();return checks;}
    private static Object invoke(Object target,String name,Class<?>[] types,Object...args)throws Exception {Method method=target.getClass().getDeclaredMethod(name,types);method.setAccessible(true);return method.invoke(target,args);}
    @SuppressWarnings("unchecked") private static <T>T field(Object target,String name)throws Exception {Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return (T)field.get(target);}
    private static <T>T onMain(Instrumentation instrumentation,Callable<T> operation)throws Exception {Object[] result=new Object[2];instrumentation.runOnMainSync(()->{try{result[0]=operation.call();}catch(Throwable error){result[1]=error;}});if(result[1]!=null)throw new Exception((Throwable)result[1]);@SuppressWarnings("unchecked")T value=(T)result[0];return value;}
    private static void await(Instrumentation instrumentation,Callable<Boolean> predicate,String message)throws Exception {long end=System.currentTimeMillis()+10000;while(System.currentTimeMillis()<end){if(onMain(instrumentation,predicate)){checks++;return;}Thread.sleep(50);}throw new AssertionError(message);}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
}
