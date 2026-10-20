package dev.ghost.nearbyim.transport;

import android.app.Instrumentation;
import android.net.NetworkCapabilities;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pInfo;
import android.os.Parcel;
import android.os.Handler;
import android.os.SystemClock;
import dev.ghost.nearbyim.core.DirectGroupCredentials;
import dev.ghost.nearbyim.core.AutomaticFileRoutes;
import java.lang.reflect.*;
import java.net.InetAddress;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Real public framework builders and waiting-state lifecycle; no claim of radio connectivity. */
public final class AndroidKnownGroupChecks {
    public static int run(Instrumentation instrumentation)throws Exception {
        DirectGroupCredentials credentials=DirectGroupCredentials.random();int checks=0;
        WifiP2pConfig owner=WifiDirectTransport.groupConfig(credentials,true);
        check(owner.getNetworkName().equals(credentials.ssid)&&owner.getPassphrase().equals(credentials.password),"Public builder retains per-attempt group parameters");checks++;
        check(owner.getGroupOwnerBand()==WifiP2pConfig.GROUP_OWNER_BAND_5GHZ,"GO explicitly requests 5GHz");checks++;
        WifiP2pConfig automatic=WifiDirectTransport.groupConfig(credentials,false);
        check(automatic.getGroupOwnerBand()==WifiP2pConfig.GROUP_OWNER_BAND_AUTO,"Confirmed-failure fallback can use AUTO");checks++;
        WifiP2pConfig client=WifiDirectTransport.groupConfig(credentials.withFrequency(5180),false);
        check(client.getGroupOwnerBand()==5180&&client.getNetworkName().equals(credentials.ssid),"Joiner scans the actual supplied group frequency");checks++;
        AtomicReference<AndroidFilePlatform> platform=new AtomicReference<>();String nonce=UUID.randomUUID().toString();
        instrumentation.runOnMainSync(()->{
            AndroidFilePlatform value=new AndroidFilePlatform(instrumentation.getTargetContext(),UUID.randomUUID().toString(),"known-group-check",UUID.randomUUID().toString());platform.set(value);value.prepareDirectGroup(nonce,false);
        });
        try{
            String report=platform.get().diagnostics();
            check(report.contains("wifi_direct_attempt="+nonce+"\n")&&report.contains("wifi_direct_phase=waiting_group_parameters\n"),"Waiting joiner has this attempt's diagnostics before credentials arrive");checks++;
            check(report.contains("wifi_direct_method=known_group\n")&&!report.contains(credentials.password)&&!report.contains(credentials.ssid),"Diagnostics identify the method without exposing credentials");checks++;
            instrumentation.runOnMainSync(()->platform.get().stopDirect());
            check(platform.get().diagnostics().contains(":stop"),"Canceled parameter wait freezes its timeline");checks++;
        }finally{instrumentation.runOnMainSync(()->platform.get().close());}
        return checks+groupSnapshots(instrumentation)+routeScopes()+retirementLifecycle(instrumentation);
    }
    private static int routeScopes() throws Exception {
        int checks=0;
        NetworkCapabilities physical=capabilities(NetworkCapabilities.TRANSPORT_WIFI,true);
        NetworkCapabilities vpn=capabilities(NetworkCapabilities.TRANSPORT_VPN,false);
        NetworkCapabilities unknown=capabilities(NetworkCapabilities.TRANSPORT_WIFI,false);
        check(WifiDirectTransport.appVpnScope(physical)==WifiDirectPolicy.VpnScope.EXCLUDED,"Framework NOT_VPN capabilities describe the caller's physical default network");checks++;
        check(WifiDirectTransport.appVpnScope(vpn)==WifiDirectPolicy.VpnScope.APPLIES,"Framework VPN transport covers the app");checks++;
        check(WifiDirectTransport.appVpnScope(unknown)==WifiDirectPolicy.VpnScope.UNKNOWN&&WifiDirectTransport.appVpnScope(null)==WifiDirectPolicy.VpnScope.UNKNOWN,"Missing capabilities cannot invent a VPN exclusion");checks++;
        var owner=InetAddress.getByName("192.168.49.1");
        var routes=java.util.List.of(new WifiDirectPolicy.InterfaceRoute("p2p0",InetAddress.getByName("192.168.49.2"),24),new WifiDirectPolicy.InterfaceRoute("tun0",InetAddress.getByName("198.18.0.1"),30));
        check(WifiDirectPolicy.prove("p2p0",owner,false,routes,false,WifiDirectTransport.appVpnScope(physical))!=null,"Actual framework VPN-exclusion observation admits a GC despite another tunnel interface");checks++;
        check(WifiDirectPolicy.prove("p2p0",owner,false,routes,false,WifiDirectTransport.appVpnScope(vpn))==null,"Actual framework VPN-covered observation still rejects an unbound GC");checks++;
        return checks;
    }
    private static NetworkCapabilities capabilities(int transport,boolean notVpn) {
        // API34's public Parcelable format starts with capabilities, forbidden
        // capabilities, transports. Keep the default framework-written tail intact.
        Parcel parcel=Parcel.obtain();try {
            new NetworkCapabilities().writeToParcel(parcel,0);parcel.setDataPosition(0);
            long bits=parcel.readLong(),flag=1L<<NetworkCapabilities.NET_CAPABILITY_NOT_VPN;
            parcel.setDataPosition(0);parcel.writeLong(notVpn?bits|flag:bits&~flag);
            parcel.readLong();parcel.writeLong(1L<<transport);parcel.setDataPosition(0);
            return NetworkCapabilities.CREATOR.createFromParcel(parcel);
        }finally{parcel.recycle();}
    }
    /** Executes the adapter's ACK/generation/Handler retirement guards without invoking a radio command. */
    private static int retirementLifecycle(Instrumentation test) throws Exception {
        AtomicReference<Throwable> failure=new AtomicReference<>();int[] checks={0};
        test.runOnMainSync(()->{
            AndroidFilePlatform platform=new AndroidFilePlatform(test.getTargetContext(),UUID.randomUUID().toString(),"retirement-check",UUID.randomUUID().toString());
            try {
                WifiDirectTransport direct=(WifiDirectTransport)field(platform,"direct");
                WifiDirectPolicy.SessionAuthority authority=(WifiDirectPolicy.SessionAuthority)field(direct,"authority");
                WifiDirectPolicy.CleanupGate gate=(WifiDirectPolicy.CleanupGate)field(direct,"cleanup");
                Class<?> groupType=Class.forName("dev.ghost.nearbyim.transport.WifiDirectTransport$CoordinatedGroup");
                Constructor<?> groupConstructor=groupType.getDeclaredConstructor(String.class,boolean.class,boolean.class,boolean.class,DirectGroupCredentials.class,java.util.function.Consumer.class);groupConstructor.setAccessible(true);
                DirectGroupCredentials credentials=DirectGroupCredentials.random();
                Object group=groupConstructor.newInstance(UUID.randomUUID().toString(),true,true,false,credentials,(java.util.function.Consumer<DirectGroupCredentials>)unused->{});
                WifiDirectPolicy.NativeCommand command=authority.issue(1,1);authority.release(true,true,0);
                check(field(direct,"retainedGroup")==null,"An unacknowledged create grants no cleanup claim");checks[0]++;
                invoke(direct,"coordinatedReply",new Class<?>[]{WifiDirectPolicy.NativeCommand.class,groupType,boolean.class,int.class},command,group,true,0);
                Object claim=field(direct,"retainedGroup");
                check(claim!=null&&!authority.blocked(),"A late accepted explicit command is retained for cleanup without reopening a canceled run");checks[0]++;
                invoke(direct,"coordinatedReply",new Class<?>[]{WifiDirectPolicy.NativeCommand.class,groupType,boolean.class,int.class},command,group,true,0);
                check(field(direct,"retainedGroup")==claim,"Duplicate ACK cannot replace a retained claim");checks[0]++;
                var retirement=new WifiDirectPolicy.GroupRetirement(credentials.ssid,credentials.password,true,"p2p0",SystemClock.elapsedRealtime()+4000);
                Class<?> workType=Class.forName("dev.ghost.nearbyim.transport.WifiDirectTransport$CleanupWork");
                Constructor<?> workConstructor=workType.getDeclaredConstructor(long.class,android.net.wifi.p2p.WifiP2pManager.Channel.class,WifiDirectPolicy.NativeCommand.class,boolean.class);workConstructor.setAccessible(true);
                long token=gate.begin();Object work=workConstructor.newInstance(token,null,command,false);set(work,"retirement",retirement);set(direct,"cleanupWork",work);
                Handler handler=(Handler)field(direct,"main");Runnable watchdog=()->{};set(work,"watchdog",watchdog);handler.postDelayed(watchdog,60_000);
                AtomicInteger resumed=new AtomicInteger();set(direct,"afterCleanup",(Runnable)resumed::incrementAndGet);
                invoke(direct,"completeRetirement",new Class<?>[]{WifiDirectPolicy.NativeCommand.class},command);
                check(handler.hasCallbacks(watchdog)&&gate.blocked()&&resumed.get()==0,"Duplicate command ACK during group retirement preserves the deadline and retry fence");checks[0]++;
                invoke(direct,"finishRetirement",new Class<?>[]{workType,boolean.class},work,false);
                check(!handler.hasCallbacks(watchdog)&&!gate.blocked()&&resumed.get()==1,"Confirmed retirement releases one waiting start and removes its watchdog");checks[0]++;
                Object next=workConstructor.newInstance(gate.begin(),null,command,false);set(next,"retirement",retirement);set(direct,"cleanupWork",next);
                invoke(direct,"finishRetirement",new Class<?>[]{workType,boolean.class},work,false);
                check(gate.blocked()&&field(direct,"cleanupWork")==next,"A late old cleanup cannot release a newer retirement");checks[0]++;
                set(direct,"afterCleanup",(Runnable)resumed::incrementAndGet);direct.stop();
                invoke(direct,"finishRetirement",new Class<?>[]{workType,boolean.class},next,false);
                check(!gate.blocked()&&resumed.get()==1,"Canceling the queued start cannot be undone by a late cleanup completion");checks[0]++;
                Method snapshot=WifiDirectTransport.class.getDeclaredMethod("retirementState",WifiDirectPolicy.GroupRetirement.class,WifiP2pGroup.class);snapshot.setAccessible(true);
                Object own=snapshot.invoke(direct,retirement,group(credentials.ssid,"p2p0",5180,true,credentials.password));
                check(own==WifiDirectPolicy.GroupRetirement.State.REMOVE,"Public Parcelable group credentials and role identify only the owned residue");checks[0]++;
                Object foreign=snapshot.invoke(direct,retirement,group(credentials.ssid,"p2p0",5180,true,"another-secret"));
                check(foreign==WifiDirectPolicy.GroupRetirement.State.FOREIGN,"Same SSID with a different secret cannot authorize removal");checks[0]++;
                check(snapshot.invoke(direct,retirement,group(null,"p2p0",5180,true,credentials.password))==WifiDirectPolicy.GroupRetirement.State.FOREIGN,"A non-null incomplete native group is never mistaken for group absence");checks[0]++;
                WifiDirectPolicy.NativeCommand rejected=authority.issue(2,2);authority.release(true,true,0);set(direct,"retainedGroup",null);
                invoke(direct,"coordinatedReply",new Class<?>[]{WifiDirectPolicy.NativeCommand.class,groupType,boolean.class,int.class},rejected,group,false,2);
                check(field(direct,"retainedGroup")==null,"A rejected command grants no cleanup claim");checks[0]++;
                Object lateClose=workConstructor.newInstance(0L,null,command,false);set(lateClose,"failed",true);
                AtomicInteger closed=new AtomicInteger();Runnable close=closed::incrementAndGet;
                invoke(lateClose,"requestClose",new Class<?>[]{boolean.class,Runnable.class},true,close);
                check(closed.get()==1,"Stop after an expired ACK watchdog immediately closes its own client");checks[0]++;
                Object pendingClose=workConstructor.newInstance(0L,null,command,false);
                invoke(pendingClose,"requestClose",new Class<?>[]{boolean.class,Runnable.class},true,close);
                check(closed.get()==1&&(boolean)field(pendingClose,"closeChannel"),"Stop before the ACK watchdog preserves the pending retirement fence");checks[0]++;
                set(pendingClose,"failed",true);invoke(pendingClose,"requestClose",new Class<?>[]{boolean.class,Runnable.class},false,close);
                check(closed.get()==2,"A later merge cannot abandon the already requested close after timeout");checks[0]++;
            }catch(Throwable error){failure.set(error);}
            finally{platform.close();}
        });
        if(failure.get()!=null)throw new Exception("Native retirement regression",failure.get());return checks[0];
    }
    /** Public API34 Parcelables enter the actual adapter handler; no RF or fake transport is used. */
    private static int groupSnapshots(Instrumentation test)throws Exception {
        int[] checks={0};AtomicReference<Throwable> failure=new AtomicReference<>();
        test.runOnMainSync(()->{
            try{
                for(String stage:new String[]{"address","role","interface","frequency","timeout","cancel","foreign","band"}){
                    AndroidFilePlatform platform=new AndroidFilePlatform(test.getTargetContext(),UUID.randomUUID().toString(),"group-snapshot",UUID.randomUUID().toString());
                    try{
                        platform.prepareDirectGroup(UUID.randomUUID().toString(),true);WifiDirectTransport direct=(WifiDirectTransport)field(platform,"direct");
                        AtomicInteger errors=new AtomicInteger();set(platform,"directFailed",(java.util.function.Consumer<Throwable>)error->errors.incrementAndGet());
                        set(platform,"directOpened",(java.util.function.Consumer<AutomaticFileRoutes.Opened>)opened->{});
                        Object lifecycle=field(direct,"lifecycle");long run=(long)invoke(lifecycle,"start",new Class<?>[]{});
                        set(direct,"run",run);set(direct,"attempt",invoke(lifecycle,"beginAttempt",new Class<?>[]{}));
                        DirectGroupCredentials parameters=DirectGroupCredentials.random();
                        Class<?> type=Class.forName("dev.ghost.nearbyim.transport.WifiDirectTransport$CoordinatedGroup");
                        Constructor<?> constructor=type.getDeclaredConstructor(String.class,boolean.class,boolean.class,boolean.class,DirectGroupCredentials.class,java.util.function.Consumer.class);constructor.setAccessible(true);
                        Object attempt=constructor.newInstance(UUID.randomUUID().toString(),true,true,false,parameters,(java.util.function.Consumer<DirectGroupCredentials>)unused->{});set(direct,"coordinated",attempt);
                        Object policy=field(attempt,"policy");invoke(policy,"begin",new Class<?>[]{});invoke(policy,"ack",new Class<?>[]{boolean.class},true);
                        WifiP2pInfo info=new WifiP2pInfo();info.groupFormed=true;info.isGroupOwner=!stage.equals("role");
                        if(stage.equals("role")||stage.equals("interface")||stage.equals("foreign")||stage.equals("frequency")||stage.equals("band"))info.groupOwnerAddress=InetAddress.getByName("192.168.77.1");
                        int actualFrequency=stage.equals("frequency")?0:stage.equals("band")?2412:5180;
                        WifiP2pGroup group=group(stage.equals("foreign")?"DIRECT-ab-unrelated":parameters.ssid,stage.equals("interface")?null:"p2p-native-fixture",actualFrequency);
                        observe(direct,run,info,group);
                        if(stage.equals("foreign")){check(errors.get()==1,"A foreign group still fails immediately instead of waiting");checks[0]++;continue;}
                        if(stage.equals("band")){check(errors.get()==1&&platform.diagnostics().contains("group_band_rejected")&&!(boolean)field(attempt,"observed"),"An actual 2.4GHz group is rejected before opening a FILE socket");checks[0]++;continue;}
                        check(errors.get()==0,"Incomplete native "+stage+" snapshot must not immediately fail");checks[0]++;
                        Runnable retry=(Runnable)field(attempt,"retry");check(retry!=null,"Incomplete group schedules a bounded reinspection");checks[0]++;
                        Runnable timeout=(Runnable)field(attempt,"timeout");check(timeout!=null,"IP readiness has an independent deadline even if a query callback never returns");checks[0]++;
                        observe(direct,run,info,group);check(field(attempt,"retry")==retry&&field(attempt,"timeout")==timeout,"Duplicate snapshots share one scheduled retry and deadline");checks[0]++;
                        check(platform.diagnostics().contains("wifi_direct_group_frequency_mhz="+actualFrequency+"\n"),"Actual frequency is retained while IP and band readiness wait");checks[0]++;
                        if(stage.equals("frequency")){check(platform.diagnostics().contains("group_wait_frequency"),"Unknown frequency is explicitly diagnosed while waiting");checks[0]++;}
                        if(stage.equals("cancel")){
                            platform.stopDirect();String stopped=platform.diagnostics();retry.run();timeout.run();
                            check(field(direct,"coordinated")==null&&errors.get()==0&&stopped.equals(platform.diagnostics()),"A queued reinspection after cancel cannot reopen or rewrite this attempt");checks[0]++;continue;
                        }
                        if(stage.equals("timeout")){
                            set(field(attempt,"readiness"),"expires",SystemClock.elapsedRealtime());timeout.run();
                            check(errors.get()==1&&platform.diagnostics().contains("group_readiness_timeout"),"IP never becoming ready fails at the existing budget with a specific trace");checks[0]++;continue;
                        }
                        info.isGroupOwner=true;info.groupOwnerAddress=InetAddress.getByName("192.168.77.1");observe(direct,run,info,group(parameters.ssid,"p2p-native-fixture"));
                        check(errors.get()==0&&(boolean)field(attempt,"observed")&&field(attempt,"retry")==null&&field(attempt,"timeout")==null,"Later complete "+stage+" snapshot advances to actual interface verification");checks[0]++;
                    }finally{platform.close();}
                }
            }catch(Throwable error){failure.set(error);}
        });
        if(failure.get()!=null)throw new Exception("Native group readiness regression",failure.get());return checks[0];
    }
    private static WifiP2pGroup group(String name,String iface){
        return group(name,iface,5180);
    }
    private static WifiP2pGroup group(String name,String iface,int frequency){
        return group(name,iface,frequency,true,null);
    }
    private static WifiP2pGroup group(String name,String iface,int frequency,boolean owner,String password){
        // API34's public Parcelable layout, from Android14 WifiP2pGroup.CREATOR.
        Parcel parcel=Parcel.obtain();try{
            parcel.writeString(name);parcel.writeParcelable(new WifiP2pDevice(),0);parcel.writeByte((byte)(owner?1:0));parcel.writeInt(0);
            parcel.writeString(password);parcel.writeString(iface);parcel.writeInt(WifiP2pGroup.NETWORK_ID_TEMPORARY);parcel.writeInt(frequency);parcel.setDataPosition(0);
            return WifiP2pGroup.CREATOR.createFromParcel(parcel);
        }finally{parcel.recycle();}
    }
    private static void observe(WifiDirectTransport direct,long run,WifiP2pInfo info,WifiP2pGroup group)throws Exception{invoke(direct,"handleCoordinatedGroup",new Class<?>[]{long.class,WifiP2pInfo.class,WifiP2pGroup.class},run,info,group);}
    private static Object field(Object value,String name)throws Exception{Field field=value.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(value);}
    private static void set(Object value,String name,Object data)throws Exception{Field field=value.getClass().getDeclaredField(name);field.setAccessible(true);field.set(value,data);}
    private static Object invoke(Object value,String name,Class<?>[] types,Object...args)throws Exception{Method method=value.getClass().getDeclaredMethod(name,types);method.setAccessible(true);return method.invoke(value,args);}
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
