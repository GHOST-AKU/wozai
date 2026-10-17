package dev.ghost.wozai;

import dev.ghost.nearbyim.core.AutomaticFileRoutes;
import dev.ghost.nearbyim.core.StreamConnection;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/** Exercises ordinary private-address TCP ownership; no native Bluetooth or Wi-Fi Direct claim. */
public final class DesktopFileRoutesTests {
    public static void main(String[] args)throws Exception{
        ordinaryInterfaceClassification();
        endpointsRefreshOnTopologyChange();
        pendingListenerIsNotPreparedEmpty();
        DesktopFileRoutes receiver=new DesktopFileRoutes(),sender=new DesktopFileRoutes();
        CompletableFuture<List<AutomaticFileRoutes.Endpoint>> endpoints=new CompletableFuture<>();
        CompletableFuture<StreamConnection> incoming=new CompletableFuture<>(),outgoing=new CompletableFuture<>();
        try{
            check(!receiver.directSupported(),"Desktop claims unsupported native Wi-Fi Direct");
            CompletableFuture<Throwable> directFailure=new CompletableFuture<>();
            receiver.openDirect(true,ignored->{throw new AssertionError("Desktop opened native Wi-Fi Direct");},directFailure::complete);
            check(directFailure.get(1,TimeUnit.SECONDS) instanceof IOException,"Unsupported Direct did not terminate immediately");
            receiver.prepareLan(endpoints::complete,incoming::complete);
            List<AutomaticFileRoutes.Endpoint> offered=endpoints.get(5,TimeUnit.SECONDS);
            check(!offered.isEmpty(),"No private ordinary LAN interface available for test");
            sender.connectLan(offered,outgoing::complete,outgoing::completeExceptionally);
            try(StreamConnection a=outgoing.get(5,TimeUnit.SECONDS);StreamConnection b=incoming.get(5,TimeUnit.SECONDS)){
                byte[] bytes={0,13,(byte)255,17};a.output().write(bytes);a.output().flush();
                check(Arrays.equals(bytes,b.input().readNBytes(bytes.length)),"LAN FILE transport changed binary bytes");
                check(a.label().equals("LAN")&&b.label().equals("LAN"),"Ordinary LAN socket mislabeled as native Direct");
            }
            receiver.close();receiver.close();
            CompletableFuture<StreamConnection> refused=new CompletableFuture<>();
            sender.connectLan(offered,refused::complete,refused::completeExceptionally);
            try{refused.get(8,TimeUnit.SECONDS);throw new AssertionError("Closed FILE listener still accepted");}catch(ExecutionException expected){}
            System.out.println("DesktopFileRoutesTests: unsupported Direct, independent private LAN sockets, binary bytes, bounded failure and listener close");
        }finally{receiver.close();sender.close();}
    }
    private static void pendingListenerIsNotPreparedEmpty()throws Exception{
        DesktopFileRoutes receiver=new DesktopFileRoutes(),sender=new DesktopFileRoutes();
        CountDownLatch occupied=new CountDownLatch(1),release=new CountDownLatch(1);
        CompletableFuture<List<AutomaticFileRoutes.Endpoint>> offered=new CompletableFuture<>();
        CompletableFuture<StreamConnection> incoming=new CompletableFuture<>(),outgoing=new CompletableFuture<>();
        java.lang.reflect.Field workerField=DesktopFileRoutes.class.getDeclaredField("worker");workerField.setAccessible(true);
        ExecutorService worker=(ExecutorService)workerField.get(receiver);
        try{
            worker.execute(()->{occupied.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException canceled){Thread.currentThread().interrupt();}});
            check(occupied.await(5,TimeUnit.SECONDS),"Listener preparation delay never acquired worker");
            receiver.prepareLan(offered::complete,incoming::complete);
            check(!offered.isDone(),"Delayed preparation callback ran before owned worker was available");
            check(receiver.currentLanEndpoints()==null,"Pending listener preparation is advertised as prepared-empty LAN");
            release.countDown();List<AutomaticFileRoutes.Endpoint> endpoints=offered.get(5,TimeUnit.SECONDS);
            check(!endpoints.isEmpty(),"Released preparation did not publish ordinary LAN endpoints");
            check(receiver.currentLanEndpoints().get(0).port==endpoints.get(0).port,"Released preparation replaced listener snapshot");
            sender.connectLan(endpoints,outgoing::complete,outgoing::completeExceptionally);
            try(StreamConnection a=outgoing.get(5,TimeUnit.SECONDS);StreamConnection b=incoming.get(5,TimeUnit.SECONDS)){
                a.output().write(61);a.output().flush();check(b.input().read()==61,"Prepared listener did not accept FILE bytes after delay");
            }
        }finally{release.countDown();receiver.close();sender.close();}
        check(receiver.currentLanEndpoints().isEmpty(),"Closed listener did not provide authoritative empty endpoints");
    }
    private static void endpointsRefreshOnTopologyChange()throws Exception{
        java.util.concurrent.atomic.AtomicReference<List<InetAddress>> visible=new java.util.concurrent.atomic.AtomicReference<>(List.of());
        java.util.function.Supplier<List<InetAddress>> snapshots=visible::get;
        java.lang.reflect.Constructor<DesktopFileRoutes> constructor;
        try{constructor=DesktopFileRoutes.class.getDeclaredConstructor(java.util.function.Supplier.class);}
        catch(NoSuchMethodException missing){throw new AssertionError("Desktop FILE LAN cannot consume changing address snapshots",missing);}
        DesktopFileRoutes receiver=constructor.newInstance(snapshots),sender=new DesktopFileRoutes();
        CompletableFuture<List<AutomaticFileRoutes.Endpoint>> initial=new CompletableFuture<>();BlockingQueue<StreamConnection> accepted=new LinkedBlockingQueue<>();
        try{
            receiver.prepareLan(initial::complete,accepted::add);
            check(initial.get(5,TimeUnit.SECONDS).isEmpty(),"Unavailable LAN was advertised at CHAT setup");
            InetAddress address=Collections.list(NetworkInterface.getNetworkInterfaces()).stream().filter(iface->{try{return iface.isUp()&&!iface.isLoopback()&&!iface.isPointToPoint()&&!iface.isVirtual()&&DesktopFileRoutes.ordinaryName(iface.getName());}catch(SocketException unavailable){return false;}}).flatMap(iface->Collections.list(iface.getInetAddresses()).stream()).filter(candidate->dev.ghost.nearbyim.core.LocalEndpoint.isLocal(candidate)&&!candidate.getHostAddress().contains("%")).findFirst().orElseThrow();
            visible.set(List.of(address));
            List<AutomaticFileRoutes.Endpoint> newlyAvailable=receiver.currentLanEndpoints();
            check(newlyAvailable!=null&&!newlyAvailable.isEmpty(),"LAN enabled after CHAT setup is still absent from current FILE endpoints");
            int port=newlyAvailable.get(0).port;
            check(newlyAvailable.get(0).host.equals(address.getHostAddress()),"Fresh FILE endpoints ignored authoritative address provider");
            CompletableFuture<StreamConnection> connected=new CompletableFuture<>();sender.connectLan(newlyAvailable,connected::complete,connected::completeExceptionally);
            try(StreamConnection a=connected.get(5,TimeUnit.SECONDS);StreamConnection b=accepted.poll(5,TimeUnit.SECONDS)){
                check(b!=null,"Fresh endpoint did not use original FILE listener/accepted callback");a.output().write(new byte[]{42,0,13});a.output().flush();check(Arrays.equals(b.input().readNBytes(3),new byte[]{42,0,13}),"Changed-topology FILE socket changed bytes");
            }
            visible.set(List.of());check(receiver.currentLanEndpoints().isEmpty(),"Disappeared LAN retains stale FILE endpoint advertisement");
            visible.set(List.of(address));check(receiver.currentLanEndpoints().get(0).port==port,"Endpoint refresh replaced the owned listener/port");
        }finally{receiver.close();sender.close();for(StreamConnection connection:accepted)connection.close();}
    }
    private static void ordinaryInterfaceClassification()throws Exception{
        java.lang.reflect.Method name,prefix;
        try{name=DesktopFileRoutes.class.getDeclaredMethod("ordinaryName",String.class);prefix=DesktopFileRoutes.class.getDeclaredMethod("sameNetwork",InetAddress.class,short.class,InetAddress.class);}
        catch(NoSuchMethodException missing){throw new AssertionError("Ordinary LAN lacks VPN/P2P exclusion and explicit subnet route binding",missing);}
        for(String value:List.of("p2p-wlan0-0","p2p0","tun0","tap2","wg0","wgp2p","utun1","tailscale0","zt123","Microsoft Wi-Fi Direct Virtual Adapter","WiFi Direct Adapter","Wi-Fi Direct","OpenVPN TAP Adapter","WireGuard Tunnel"))check(!(boolean)name.invoke(null,value),"Non-LAN interface accepted: "+value);
        for(String value:List.of("eth0","enp3s0","wlan0","Wi-Fi","Ethernet"))check((boolean)name.invoke(null,value),"Ordinary LAN interface rejected: "+value);
        check((boolean)prefix.invoke(null,InetAddress.getByName("192.168.10.2"),(short)24,InetAddress.getByName("192.168.10.200")),"Same ordinary LAN prefix rejected");
        check(!(boolean)prefix.invoke(null,InetAddress.getByName("192.168.10.2"),(short)24,InetAddress.getByName("192.168.49.1")),"Unrelated Direct subnet accepted as ordinary LAN");
        check(!(boolean)prefix.invoke(null,InetAddress.getByName("192.168.10.2"),(short)0,InetAddress.getByName("192.168.49.1")),"Default route accepted as proven ordinary LAN");
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
