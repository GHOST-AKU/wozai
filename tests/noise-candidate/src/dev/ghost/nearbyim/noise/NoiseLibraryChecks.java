package dev.ghost.nearbyim.noise;

import com.southernstorm.noise.protocol.*;
import java.security.*;
import java.util.*;
import javax.crypto.*;

/** Library acceptance only: no application identities, consent or radio claims. */
public final class NoiseLibraryChecks {
    private static final String SUITE="Noise_XX_25519_AESGCM_SHA256";
    private static int checks;
    private interface Action {void run()throws Exception;}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
    private static void rejects(Action action)throws Exception {try{action.run();}catch(BadPaddingException|IllegalStateException|ShortBufferException expected){checks++;return;}throw new AssertionError("Invalid AEAD operation accepted");}
    private static byte[] hex(String value){byte[] bytes=new byte[value.length()/2];for(int i=0;i<bytes.length;i++)bytes[i]=(byte)Integer.parseInt(value.substring(i*2,i*2+2),16);return bytes;}
    public static synchronized int run()throws Exception {
        checks=0;x25519Vectors();vector(true,true);vector(true,false);vector(false,true);differential();lowOrder();return checks;
    }
    public static void main(String[] args)throws Exception {System.out.println("Noise candidate: "+run()+" vector/interop/AEAD checks passed (library gate, not production E2E acceptance)");}
    private static HandshakeState handshake(boolean initiator,boolean candidate)throws Exception {
        HandshakeState state=candidate?new HandshakeState(SUITE,initiator?HandshakeState.INITIATOR:HandshakeState.RESPONDER,new JcaAesGcmCipherState()):new HandshakeState(SUITE,initiator?HandshakeState.INITIATOR:HandshakeState.RESPONDER);
        state.getLocalKeyPair().setPrivateKey(hex(initiator?VectorFixture.INIT_STATIC:VectorFixture.RESP_STATIC),0);
        state.getFixedEphemeralKey().setPrivateKey(hex(initiator?VectorFixture.INIT_EPHEMERAL:VectorFixture.RESP_EPHEMERAL),0);
        byte[] prologue=hex(initiator?VectorFixture.INIT_PROLOGUE:VectorFixture.RESP_PROLOGUE);state.setPrologue(prologue,0,prologue.length);state.start();return state;
    }
    private static void vector(boolean candidateA,boolean candidateB)throws Exception {
        HandshakeState a=handshake(true,candidateA),b=handshake(false,candidateB);CipherStatePair pa=null,pb=null;
        try {
            byte[] output=new byte[65535],clear=new byte[65535];
            for(int i=0;i<3;i++) {
                HandshakeState sender=i%2==0?a:b,receiver=i%2==0?b:a;byte[] plain=hex(VectorFixture.MESSAGES[i][0]),expected=hex(VectorFixture.MESSAGES[i][1]);
                int n=sender.writeMessage(output,0,plain,0,plain.length);check(Arrays.equals(Arrays.copyOf(output,n),expected),"Independent handshake vector changed");
                n=receiver.readMessage(output,0,n,clear,0);check(Arrays.equals(Arrays.copyOf(clear,n),plain),"Handshake payload changed");
            }
            check(Arrays.equals(a.getHandshakeHash(),hex(VectorFixture.HANDSHAKE_HASH))&&Arrays.equals(b.getHandshakeHash(),hex(VectorFixture.HANDSHAKE_HASH)),"Independent transcript hash changed");
            pa=a.split();pb=b.split();
            for(int i=3;i<6;i++) {
                CipherState sender=i%2==0?pa.getSender():pb.getSender(),receiver=i%2==0?pb.getReceiver():pa.getReceiver();byte[] plain=hex(VectorFixture.MESSAGES[i][0]),expected=hex(VectorFixture.MESSAGES[i][1]);
                int n=sender.encryptWithAd(null,plain,0,output,0,plain.length);check(Arrays.equals(Arrays.copyOf(output,n),expected),"Independent transport vector changed");
                n=receiver.decryptWithAd(null,output,0,clear,0,n);check(Arrays.equals(Arrays.copyOf(clear,n),plain),"Transport payload changed");
            }
        }finally{a.destroy();b.destroy();if(pa!=null)pa.destroy();if(pb!=null)pb.destroy();}
    }
    private static void lowOrder()throws Exception {
        byte[][] points=new byte[6][32];points[1][0]=1;points[2][0]=1;points[2][31]=(byte)128;points[3][31]=(byte)128;
        Arrays.fill(points[4],(byte)255);points[4][0]=(byte)236;points[4][31]=127;
        Arrays.fill(points[5],(byte)255);points[5][0]=(byte)237;points[5][31]=127;
        for(int index=0;index<points.length;index++) {
            byte[] point=points[index];
            HandshakeState responder=handshake(false,true);
            try{rejects(()->{byte[] output=new byte[512];responder.readMessage(point,0,point.length,output,0);responder.writeMessage(output,0,new byte[0],0,0);});}
            catch(AssertionError failure){throw new AssertionError("Low-order encoding fixture "+index+" was accepted",failure);}
            finally{responder.destroy();}
        }
    }
    private static void x25519Vectors()throws Exception {
        // RFC 7748 section 5.2, including the second vector's high input bit.
        String[][] vectors={
            {"a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4","e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c","c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552"},
            {"4b66e9d4d1b4673c5ad22691957d6af5c11b6421e0ea01d42ca4169e7918ba0d","e5210f12786811d3f4b7959d0538ae2c31dbe7106fc03c3efc4cd549c715a493","95cbde9476e8907d7aade45cb4b873f88b595a68799fa152e6f8f7647aac7957"}
        };
        for(String[] vector:vectors) {
            DHState local=Noise.createDH("25519"),remote=Noise.createDH("25519");
            try {
                local.setPrivateKey(hex(vector[0]),0);byte[] publicKey=hex(vector[1]);remote.setPublicKey(publicKey,0);byte[] shared=new byte[32];local.calculate(shared,0,remote);
                check(Arrays.equals(shared,hex(vector[2])),"RFC 7748 X25519 known-answer vector changed");
                publicKey[31]^=(byte)128;remote.setPublicKey(publicKey,0);local.calculate(shared,0,remote);
                check(Arrays.equals(shared,hex(vector[2])),"RFC 7748 X25519 input high-bit masking changed");
            }finally{local.destroy();remote.destroy();}
        }
    }
    private static void differential()throws Exception {
        Random random=new Random(32);byte[] key=new byte[32],ad=new byte[32];random.nextBytes(key);random.nextBytes(ad);
        for(long nonce:new long[]{0,7,Long.MIN_VALUE,-2})for(int size:new int[]{0,1,16,16384,32768,49152}) {
            CipherState reference=Noise.createCipher("AESGCM"),candidate=new JcaAesGcmCipherState(),receiver=new JcaAesGcmCipherState();
            try {reference.initializeKey(key,0);candidate.initializeKey(key,0);receiver.initializeKey(key,0);reference.setNonce(nonce);candidate.setNonce(nonce);receiver.setNonce(nonce);
                byte[] plain=new byte[size],expected=new byte[size+16],actual=new byte[size+16],clear=new byte[size];random.nextBytes(plain);
                int n=reference.encryptWithAd(ad,plain,0,expected,0,size);check(candidate.encryptWithAd(ad,plain,0,actual,0,size)==n&&Arrays.equals(expected,actual),"System AEAD differs from reference");
                byte[] bad=actual.clone();bad[bad.length-1]^=1;rejects(()->receiver.decryptWithAd(ad,bad,0,clear,0,bad.length));
                check(receiver.decryptWithAd(ad,actual,0,clear,0,actual.length)==size&&Arrays.equals(plain,clear),"Bad tag advanced nonce or valid plaintext changed");
                rejects(()->receiver.decryptWithAd(ad,actual,0,clear,0,actual.length));
            }finally{reference.destroy();candidate.destroy();receiver.destroy();}
        }
        CipherState state=new JcaAesGcmCipherState();state.initializeKey(key,0);state.setNonce(-1);rejects(()->state.encryptWithAd(null,new byte[0],0,new byte[16],0,0));state.destroy();state.destroy();rejects(()->state.initializeKey(key,0));Arrays.fill(key,(byte)0);
    }
}
