package dev.ghost.nearbyim.noise;

import com.southernstorm.noise.protocol.*;
import java.util.*;
import javax.crypto.Cipher;

/** Controlled in-process library measurement; never a radio/file acceptance report. */
public final class NoiseBenchmark {
    private static final String SUITE="Noise_XX_25519_AESGCM_SHA256";
    public static void main(String[] args)throws Exception {
        System.out.println("provider="+Cipher.getInstance("AES/GCM/NoPadding").getProvider().getName()
                +", arch="+System.getProperty("os.arch")+", java="+System.getProperty("java.version"));
        byte[] aKey=new byte[32],bKey=new byte[32];Noise.random(aKey);Noise.random(bKey);
        try {
            for(int round=-1;round<3;round++) {
                long began=System.nanoTime();long handshakeNanos=0;long bytes=0;long transferNanos=0;
                for(int connection=0;connection<5;connection++) {
                    HandshakeState a=new HandshakeState(SUITE,HandshakeState.INITIATOR,new JcaAesGcmCipherState());
                    HandshakeState b=new HandshakeState(SUITE,HandshakeState.RESPONDER,new JcaAesGcmCipherState());
                    CipherStatePair pa=null,pb=null;
                    try {
                        a.getLocalKeyPair().setPrivateKey(aKey,0);b.getLocalKeyPair().setPrivateKey(bKey,0);
                        a.start();b.start();byte[] wire=new byte[32784],plain=new byte[32768],clear=new byte[32768];
                        new Random(connection).nextBytes(plain);long start=System.nanoTime();
                        int n=a.writeMessage(wire,0,null,0,0);b.readMessage(wire,0,n,clear,0);
                        n=b.writeMessage(wire,0,null,0,0);a.readMessage(wire,0,n,clear,0);
                        n=a.writeMessage(wire,0,null,0,0);b.readMessage(wire,0,n,clear,0);
                        if(!Arrays.equals(a.getHandshakeHash(),b.getHandshakeHash()))throw new AssertionError("Handshake mismatch");
                        pa=a.split();pb=b.split();handshakeNanos+=System.nanoTime()-start;
                        start=System.nanoTime();
                        for(int record=0;record<128;record++) {
                            CipherState sender=record%2==0?pa.getSender():pb.getSender(),receiver=record%2==0?pb.getReceiver():pa.getReceiver();
                            n=sender.encryptWithAd(null,plain,0,wire,0,plain.length);
                            if(receiver.decryptWithAd(null,wire,0,clear,0,n)!=plain.length||!Arrays.equals(plain,clear))throw new AssertionError("AEAD data mismatch");
                            bytes+=plain.length;
                        }
                        transferNanos+=System.nanoTime()-start;
                    }finally{a.destroy();b.destroy();if(pa!=null)pa.destroy();if(pb!=null)pb.destroy();}
                }
                if(round>=0)System.out.printf(Locale.ROOT,"round=%d, connections=5, payload_bytes=%d, handshake_mean_ms=%.3f, encrypt_decrypt_mib_s=%.3f, total_ms=%.3f%n",
                        round+1,bytes,handshakeNanos/5e6,bytes*1e9/transferNanos/1048576.0,(System.nanoTime()-began)/1e6);
            }
        }finally{Arrays.fill(aKey,(byte)0);Arrays.fill(bKey,(byte)0);}
    }
}
