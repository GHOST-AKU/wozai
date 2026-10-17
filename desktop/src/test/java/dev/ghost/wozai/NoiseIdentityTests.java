package dev.ghost.wozai;
import dev.ghost.nearbyim.core.DeviceIdentity;
import java.nio.file.*;
import java.util.*;
public final class NoiseIdentityTests {
    public static void main(String[] args)throws Exception {
        Path root=Files.createTempDirectory("noise-key-tests-");Path file=root.resolve("noise.properties");byte[] a=null,b=null;
        try {
            DeviceIdentity identity=DeviceIdentity.generate();a=DesktopNoiseIdentity.load(file,identity);b=DesktopNoiseIdentity.load(file,identity);
            if(a.length!=32||!Arrays.equals(a,b))throw new AssertionError("Noise key changes across loads");
            boolean rejected=false;try{DesktopNoiseIdentity.load(file,DeviceIdentity.generate());}catch(java.io.IOException expected){rejected=true;}
            if(!rejected||!Arrays.equals(a,DesktopNoiseIdentity.load(file,identity)))throw new AssertionError("Wrong root accepted or existing key reset");
            if(!DesktopIdentity.windows()&&!Files.getPosixFilePermissions(file).equals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")))throw new AssertionError("Private key permissions too broad");
            System.out.println("Noise identity: persistent private key, root binding and file protection passed");
        }finally{if(a!=null)Arrays.fill(a,(byte)0);if(b!=null)Arrays.fill(b,(byte)0);Files.deleteIfExists(file);Files.deleteIfExists(root);}
    }
}
