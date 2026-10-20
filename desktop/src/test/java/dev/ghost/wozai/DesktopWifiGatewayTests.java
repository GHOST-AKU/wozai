package dev.ghost.wozai;
import java.net.*;
import java.util.*;
public final class DesktopWifiGatewayTests {
    public static void main(String[] args)throws Exception {
        InetAddress peer=InetAddress.getByName("192.168.43.1");
        check(DesktopWifiGateway.linuxRoute(List.of("wlan0 00000000 012BA8C0 0003"),"wlan0",peer),"Native gateway byte order must identify the communicating endpoint");
        for(String row:List.of("eth0 00000000 012BA8C0 0003","wlan0 002BA8C0 012BA8C0 0003","wlan0 00000000 022BA8C0 0003","wlan0 00000000 012BA8C0 0000","wlan0 00000000 INVALID 0003"))
            check(!DesktopWifiGateway.linuxRoute(List.of(row),"wlan0",peer),"Unrelated or invalid OS route cannot identify an endpoint hotspot");
        System.out.println("Desktop Wi-Fi gateway route evidence passed");
    }
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
