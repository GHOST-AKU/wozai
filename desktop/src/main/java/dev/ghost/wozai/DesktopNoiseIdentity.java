package dev.ghost.wozai;
import dev.ghost.nearbyim.core.DeviceIdentity;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.security.SecureRandom;

/** Preserve the P-256 root; store a separate X25519 key with existing OS protection. */
public final class DesktopNoiseIdentity {
    private DesktopNoiseIdentity(){}
    public static synchronized byte[] load(Path file,DeviceIdentity root)throws IOException {
        try {
            Properties properties;
            String protection=DesktopIdentity.windows()?"dpapi":"posix";
            if(!Files.exists(file,LinkOption.NOFOLLOW_LINKS)) {
                properties=new Properties();byte[] key=new byte[32],protectedBytes=null;new SecureRandom().nextBytes(key);
                try {protectedBytes=DesktopIdentity.windows()?DesktopIdentity.dpapi(key,true):key.clone();
                    properties.setProperty("key",Base64.getEncoder().encodeToString(protectedBytes));properties.setProperty("root",root.publicKey());properties.setProperty("protection",protection);AtomicFiles.write(file,properties);
                }finally{Arrays.fill(key,(byte)0);if(protectedBytes!=null)Arrays.fill(protectedBytes,(byte)0);}
            }else properties=AtomicFiles.read(file);
            if(!root.publicKey().equals(AtomicFiles.required(properties,"root"))||!protection.equals(AtomicFiles.required(properties,"protection")))throw new IOException("Noise key root or platform mismatch");
            byte[] bytes=Base64.getDecoder().decode(AtomicFiles.required(properties,"key"));
            if(DesktopIdentity.windows()){byte[] protectedBytes=bytes;try{bytes=DesktopIdentity.dpapi(protectedBytes,false);}finally{Arrays.fill(protectedBytes,(byte)0);}}
            if(bytes.length!=32){Arrays.fill(bytes,(byte)0);throw new IOException("Invalid Noise private key size");}return bytes;
        }catch(IllegalArgumentException invalid){throw new IOException("Invalid Noise identity encoding",invalid);}
    }
}
