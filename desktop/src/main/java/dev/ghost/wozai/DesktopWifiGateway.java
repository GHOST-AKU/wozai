package dev.ghost.wozai;

import java.net.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import dev.ghost.nearbyim.core.LocalEndpoint;

/** OS route evidence for a Wi-Fi client connected to the communicating hotspot host. */
final class DesktopWifiGateway {
    static boolean matches(InetAddress local,InetAddress peer){
        if(!(local instanceof Inet4Address)||!(peer instanceof Inet4Address)||!LocalEndpoint.isLocal(local)||!LocalEndpoint.isLocal(peer))return false;
        try{
            String os=System.getProperty("os.name","").toLowerCase(Locale.ROOT);
            if(os.contains("linux")){
                NetworkInterface iface=NetworkInterface.getByInetAddress(local);
                if(iface==null||!Files.isDirectory(Path.of("/sys/class/net",iface.getName(),"wireless")))return false;
                try(var lines=Files.lines(Path.of("/proc/net/route"))){return linuxRoute(lines.limit(2048).toList(),iface.getName(),peer);}
            }
            if(os.contains("windows"))return windows(local.getHostAddress(),peer.getHostAddress());
        }catch(Exception unavailable){if(unavailable instanceof InterruptedException)Thread.currentThread().interrupt();}
        return false;
    }
    static boolean linuxRoute(List<String> lines,String iface,InetAddress peer){
        for(String line:lines){String[] columns=line.trim().split("\\s+");if(columns.length<4||!columns[0].equals(iface)||!columns[1].equals("00000000"))continue;
            try{long encoded=Long.parseUnsignedLong(columns[2],16);int flags=Integer.parseInt(columns[3],16);byte[] ip=peer.getAddress();
                if(ip.length==4&&(flags&3)==3&&encoded==((ip[0]&255L)|((ip[1]&255L)<<8)|((ip[2]&255L)<<16)|((ip[3]&255L)<<24)))return true;
            }catch(NumberFormatException invalid){}
        }return false;
    }
    private static boolean windows(String local,String peer)throws Exception{
        // Addresses come from Inet4Address, not user-supplied shell text. No admin changes.
        String script="$ErrorActionPreference='Stop';$c=Get-NetIPConfiguration -All|Where-Object {@($_.IPv4Address.IPAddress) -contains '"+local+"'};foreach($n in $c){if(([int]$n.NetAdapter.NdisPhysicalMedium -in @(1,9)) -and (@($n.IPv4DefaultGateway.NextHop) -contains '"+peer+"')){[Console]::Write('yes');break}}";
        Process process=new ProcessBuilder("powershell.exe","-NoProfile","-NonInteractive","-Command",script).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try{if(!process.waitFor(2,TimeUnit.SECONDS))return false;return process.exitValue()==0&&new String(process.getInputStream().readNBytes(16),StandardCharsets.US_ASCII).equals("yes");}
        finally{process.destroyForcibly();}
    }
}
