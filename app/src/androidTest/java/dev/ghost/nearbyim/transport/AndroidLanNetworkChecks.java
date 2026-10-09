package dev.ghost.nearbyim.transport;
import android.net.*;
import java.io.*;
import java.net.*;
/** Platform route objects and controlled socket order; no hotspot/radio acceptance. */
public final class AndroidLanNetworkChecks {
    private static int checks;
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
    public static int run()throws Exception {
        checks=0;InetAddress host=InetAddress.getByName("192.168.43.2");
        java.util.List<IpPrefix> local=java.util.Collections.singletonList(new IpPrefix(InetAddress.getByName("192.168.43.0"),24));
        check(LanTransport.routeScore(true,false,false,local,host)==24,"Unvalidated hotspot Wi-Fi was excluded");
        check(LanTransport.routeScore(false,false,false,local,host)<0,"Cellular internet took a local Wi-Fi route");
        check(LanTransport.routeScore(true,false,true,local,host)<0,"VPN was treated as the selected physical LAN");
        check(LanTransport.routeScore(true,false,false,local,InetAddress.getByName("192.168.44.2"))<0,"Unrelated local subnet matched");
        check(LanTransport.networkScore(null,new LinkProperties(),host)<0&&LanTransport.networkScore(new NetworkCapabilities(),null,host)<0,"Missing network state was accepted");
        try(ServerSocket server=new ServerSocket(0,1,InetAddress.getLoopbackAddress());Socket socket=new Socket()) {
            boolean[] bound={false};LanTransport.bindAndConnect(socket,new InetSocketAddress(InetAddress.getLoopbackAddress(),server.getLocalPort()),candidate->{check(!candidate.isConnected(),"Binding occurred after connect");bound[0]=true;});
            try(Socket accepted=server.accept()){check(bound[0]&&socket.isConnected(),"Bound connection did not reach the selected endpoint");}
        }
        try(Socket rejected=new Socket()) {
            try{LanTransport.bindAndConnect(rejected,new InetSocketAddress(InetAddress.getLoopbackAddress(),9),candidate->{throw new SecurityException("Controlled binding denial");});throw new AssertionError("Denied binding fell back to another route");}
            catch(SecurityException expected){check(rejected.isClosed()&&!rejected.isConnected(),"Denied binding leaked a socket or connected without binding");}
        }
        return checks;
    }
}
