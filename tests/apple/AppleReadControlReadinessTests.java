package dev.ghost.nearbyim.core;
import java.net.*;
import java.nio.file.*;
import java.util.concurrent.*;

/** The independent UI driver must be reachable before consent/text processing finishes. */
public final class AppleReadControlReadinessTests {
    public static void main(String[] args)throws Exception {
        Path root=Files.createTempDirectory("wozai-ui-control-");Path port=root.resolve("peer.port");
        Process child=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",System.getProperty("java.class.path"),"dev.ghost.nearbyim.core.AppleInteropPeer","ui-server",port.toString(),"--read-ui").redirectErrorStream(true).redirectOutput(root.resolve("peer.log").toFile()).start();
        try{
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(!Files.exists(port)){if(!child.isAlive()||System.nanoTime()>end)throw new AssertionError("UI peer failed to start: "+Files.readString(root.resolve("peer.log")));Thread.sleep(10);}
            try(Socket chat=new Socket("127.0.0.1",Integer.parseInt(Files.readString(port)))){
                // Hold the app handshake; the driver must connect without waiting for chat readiness.
                end=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);boolean ready=false;
                while(System.nanoTime()<end){try(Socket control=new Socket("127.0.0.1",47832)){ready=true;break;}catch(ConnectException pending){Thread.sleep(10);}}
                if(!ready)throw new AssertionError("UI control listener missing while NIM4 handshake is waiting");
            }
            System.out.println("AppleReadControlReadinessTests: control is bound before chat consent/text completion");
        }finally{child.destroyForcibly();child.waitFor(3,TimeUnit.SECONDS);try(var paths=Files.walk(root)){for(Path p:paths.sorted(java.util.Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
    }
}
