package dev.ghost.nearbyim.noise;

import com.southernstorm.noise.protocol.*;
import dev.ghost.nearbyim.core.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static dev.ghost.nearbyim.core.TransferDiagnostics.Stage.*;

/** Authenticated NIM4 records over a TCP or RFCOMM byte stream. */
public final class NoiseRecordChannel implements AutoCloseable {
    private static final String SUITE="Noise_XX_25519_AESGCM_SHA256";
    private static final byte[] PROLOGUE="wozai-nim4\0Noise_XX_25519_AESGCM_SHA256\0file-v2-32k\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] ROOT_DOMAIN="wozai-root-proof-v4\0".getBytes(StandardCharsets.US_ASCII),GENERATION_DOMAIN="wozai-connection-v4\0".getBytes(StandardCharsets.US_ASCII);
    private static final int MAGIC=0x4e494d34,VERSION=4,HANDSHAKE=1,ROOT_PROOF=2,RECORD=3,MAX_PLAIN=48*1024;
    private static final long MAX_FILE=10_737_418_240L;
    private final TransferDiagnostics metrics=new TransferDiagnostics();
    private final StreamConnection connection;
    private final boolean initiator;
    private final DeviceIdentity identity;
    private final String localId,expectedPeerId,expectedRoot;
    private final byte[] localClaim,noisePrivate;
    private final AtomicBoolean closed=new AtomicBoolean(),started=new AtomicBoolean();
    private final Object readLock=new Object(),writeLock=new Object();
    private InputStream input;private OutputStream output;private CipherStatePair pair;
    private volatile boolean verified;
    private Frame remoteHello;private String remoteRoot,generation;

    public NoiseRecordChannel(StreamConnection connection,boolean initiator,DeviceIdentity identity,String localId,String nickname,byte[] noisePrivate,String expectedPeerId,String expectedRoot)throws IOException {
        this.connection=Objects.requireNonNull(connection);this.initiator=initiator;this.identity=Objects.requireNonNull(identity);
        validateId(localId);if(expectedPeerId!=null)validateId(expectedPeerId);validateNickname(nickname);
        if(noisePrivate==null||noisePrivate.length!=32)throw new IOException("Invalid static key size");
        this.localId=localId;this.expectedPeerId=expectedPeerId;this.expectedRoot=expectedRoot;this.noisePrivate=noisePrivate.clone();
        DHState local=null;
        try {
            local=Noise.createDH("25519");local.setPrivateKey(noisePrivate,0);byte[] publicKey=new byte[32];local.getPublicKey(publicKey,0);
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
            out.writeByte(VERSION);out.writeByte(initiator?0:1);writeBytes(out,utf8(localId));writeBytes(out,utf8(nickname));writeBytes(out,Base64.getDecoder().decode(identity.publicKey()));
            out.write(publicKey);out.writeInt(3);out.writeLong(MAX_FILE);out.writeInt(MAX_PLAIN);localClaim=bytes.toByteArray();
        }catch(GeneralSecurityException|IllegalArgumentException e){Arrays.fill(this.noisePrivate,(byte)0);throw new IOException("Cannot initialize Noise identity",e);}
        finally{if(local!=null)local.destroy();}
    }
    public void establish()throws IOException {
        if(!started.compareAndSet(false,true))throw new IOException("Repeated handshake");
        synchronized(readLock){synchronized(writeLock){
            ensureOpen();HandshakeState handshake=null;
            try {
                input=connection.input();output=connection.output();
                handshake=new HandshakeState(SUITE,initiator?HandshakeState.INITIATOR:HandshakeState.RESPONDER,new JcaAesGcmCipherState());
                handshake.getLocalKeyPair().setPrivateKey(noisePrivate,0);Arrays.fill(noisePrivate,(byte)0);
                handshake.setPrologue(PROLOGUE,0,PROLOGUE.length);handshake.start();byte[] remoteClaim;
                if(initiator) {
                    sendHandshake(handshake,new byte[0]);remoteClaim=readHandshake(handshake);sendHandshake(handshake,localClaim);
                } else {
                    if(readHandshake(handshake).length!=0)throw new IOException("Unexpected initial payload");
                    sendHandshake(handshake,localClaim);remoteClaim=readHandshake(handshake);
                }
                Claim peer=parseClaim(remoteClaim);byte[] noisePublic=new byte[32];handshake.getRemotePublicKey().getPublicKey(noisePublic,0);
                if(!Arrays.equals(peer.noisePublic,noisePublic))throw new IOException("Static key binding mismatch");
                byte[] first=initiator?localClaim:remoteClaim,second=initiator?remoteClaim:localClaim,hash=handshake.getHandshakeHash().clone();
                byte[] binding=binding(ROOT_DOMAIN,first,second,hash);pair=handshake.split();
                if(initiator){sendProof(binding,0);verifyProof(peer,binding,1);}else{verifyProof(peer,binding,0);sendProof(binding,1);}
                if(expectedPeerId!=null&&!expectedPeerId.equals(peer.id)||expectedRoot!=null&&!expectedRoot.equals(peer.root))throw new IOException("Pinned identity mismatch");
                ensureOpen();remoteHello=new Frame(Frame.HELLO,peer.id,peer.nickname,System.currentTimeMillis());remoteRoot=peer.root;
                generation=hex(MessageDigest.getInstance("SHA-256").digest(binding(GENERATION_DOMAIN,first,second,hash)));
                verified=true;
            }catch(GeneralSecurityException|RuntimeException e){close();throw new IOException("Noise authentication failed",e);}
            catch(IOException e){close();throw e;}
            finally{Arrays.fill(noisePrivate,(byte)0);if(handshake!=null)handshake.destroy();}
        }}
    }
    public boolean verified(){return verified&&!closed.get();}
    public Frame remoteHello(){return verified()?remoteHello:null;}
    public String remotePublicKey(){return verified()?remoteRoot:null;}
    public String diagnostics(){return metrics.snapshot();}
    public String connectionGeneration(){return verified()?generation:null;}
    public void write(byte[] plaintext)throws IOException {
        synchronized(writeLock){if(!verified())throw new IOException("Unauthenticated write");if(plaintext==null||plaintext.length>MAX_PLAIN)throw new IOException("Record too large");
            try{sendEncrypted(RECORD,plaintext);}catch(GeneralSecurityException|RuntimeException e){close();throw new IOException("Encrypted write failed",e);}catch(IOException e){close();throw e;}}
    }
    public byte[] read()throws IOException {
        synchronized(readLock){if(!verified())throw new IOException("Unauthenticated read");
            try{return readEncrypted(RECORD,MAX_PLAIN);}catch(GeneralSecurityException|RuntimeException e){close();throw new IOException("Encrypted record rejected",e);}catch(IOException e){close();throw e;}}
    }
    private void sendHandshake(HandshakeState handshake,byte[] payload)throws GeneralSecurityException,IOException {
        byte[] bytes=new byte[2048];int n=handshake.writeMessage(bytes,0,payload,0,payload.length);writeEnvelope(HANDSHAKE,Arrays.copyOf(bytes,n));
    }
    private byte[] readHandshake(HandshakeState handshake)throws GeneralSecurityException,IOException {
        byte[] bytes=readEnvelope(HANDSHAKE,2048),plain=new byte[2048];int n=handshake.readMessage(bytes,0,bytes.length,plain,0);return Arrays.copyOf(plain,n);
    }
    private void sendProof(byte[] binding,int role)throws GeneralSecurityException,IOException {sendEncrypted(ROOT_PROOF,identity.sign(proof(binding,role)));}
    private void verifyProof(Claim peer,byte[] binding,int role)throws GeneralSecurityException,IOException {DeviceIdentity.verifyProof(peer.key,proof(binding,role),readEncrypted(ROOT_PROOF,80));}
    private static byte[] proof(byte[] binding,int role)throws IOException {ByteArrayOutputStream bytes=new ByteArrayOutputStream();bytes.write(binding);bytes.write(role);return bytes.toByteArray();}
    private void sendEncrypted(int kind,byte[] plaintext)throws GeneralSecurityException,IOException {
        ensureOpen();int size=plaintext.length+16;byte[] header=aad(kind,size),record=new byte[header.length+size];System.arraycopy(header,0,record,0,header.length);
        long began=metrics.begin(ENCRYPT);int n;try{n=pair.getSender().encryptWithAd(header,plaintext,0,record,header.length,plaintext.length);}finally{metrics.end(ENCRYPT,began,plaintext.length);}
        if(n!=size)throw new IOException("Invalid encrypted size");began=metrics.begin(SOCKET_WRITE);try{output.write(record);output.flush();}finally{metrics.end(SOCKET_WRITE,began,record.length);}
    }
    private byte[] readEncrypted(int kind,int maximum)throws GeneralSecurityException,IOException {
        byte[] ciphertext=readEnvelope(kind,maximum+16);if(ciphertext.length<16)throw new IOException("Truncated encrypted record");byte[] plaintext=new byte[ciphertext.length-16];
        long began=metrics.begin(DECRYPT);int n;try{n=pair.getReceiver().decryptWithAd(aad(kind,ciphertext.length),ciphertext,0,plaintext,0,ciphertext.length);}finally{metrics.end(DECRYPT,began,ciphertext.length);}
        if(n!=plaintext.length)throw new IOException("Invalid clear size");return plaintext;
    }
    private static byte[] aad(int kind,int size)throws IOException {ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);out.writeInt(size+6);out.writeInt(MAGIC);out.writeByte(VERSION);out.writeByte(kind);return bytes.toByteArray();}
    private void writeEnvelope(int kind,byte[] bytes)throws IOException {ensureOpen();DataOutputStream out=new DataOutputStream(output);out.write(aad(kind,bytes.length));out.write(bytes);out.flush();}
    private byte[] readEnvelope(int kind,int maximum)throws IOException {
        ensureOpen();DataInputStream in=new DataInputStream(input);
        // DataInputStream.readInt/readUnsignedByte issue scalar reads on an unbuffered socket.
        // Read the length separately so an invalid length is rejected before waiting for the rest.
        byte[] header=new byte[10];long began=metrics.begin(SOCKET_HEADER);int size;try{in.readFully(header,0,4);size=ByteBuffer.wrap(header).getInt();
            if(size<6||size>maximum+6)throw new IOException("Invalid NIM4 envelope length");
            in.readFully(header,4,6);
        }finally{metrics.end(SOCKET_HEADER,began,10);}
        if(ByteBuffer.wrap(header,4,4).getInt()!=MAGIC||(header[8]&255)!=VERSION||(header[9]&255)!=kind)throw new UnsupportedProtocolException();
        byte[] bytes=new byte[size-6];began=metrics.begin(SOCKET_READ);try{in.readFully(bytes);}finally{metrics.end(SOCKET_READ,began,bytes.length);}return bytes;
    }
    private Claim parseClaim(byte[] bytes)throws IOException {
        DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes));if(in.readUnsignedByte()!=VERSION||in.readUnsignedByte()!=(initiator?1:0))throw new IOException("Invalid peer role");
        String id=text(in,36),nickname=text(in,128);validateId(id);validateNickname(nickname);if(localId.equals(id))throw new IOException("Reflected identity");
        byte[] key=readBytes(in,256),noisePublic=new byte[32];in.readFully(noisePublic);String root=Base64.getEncoder().encodeToString(key);
        if(in.readInt()!=3||in.readLong()!=MAX_FILE||in.readInt()!=MAX_PLAIN||in.available()!=0)throw new IOException("Unsupported peer capabilities");
        return new Claim(id,nickname,root,key,noisePublic);
    }
    private static final class Claim {final String id,nickname,root;final byte[] key,noisePublic;Claim(String id,String nickname,String root,byte[] key,byte[] noisePublic){this.id=id;this.nickname=nickname;this.root=root;this.key=key;this.noisePublic=noisePublic;}}
    private static byte[] binding(byte[] domain,byte[] first,byte[] second,byte[] hash)throws IOException {ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);out.write(domain);writeBytes(out,first);writeBytes(out,second);writeBytes(out,hash);return bytes.toByteArray();}
    private static void writeBytes(DataOutputStream out,byte[] bytes)throws IOException {out.writeShort(bytes.length);out.write(bytes);}
    private static byte[] readBytes(DataInputStream in,int maximum)throws IOException {int n=in.readUnsignedShort();if(n>maximum||n>in.available())throw new IOException("Invalid claim size");byte[] bytes=new byte[n];in.readFully(bytes);return bytes;}
    private static String text(DataInputStream in,int maximum)throws IOException {try{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(readBytes(in,maximum))).toString();}catch(CharacterCodingException e){throw new IOException("Invalid claim UTF-8",e);}}
    private static byte[] utf8(String value)throws IOException {try{ByteBuffer buffer=StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value));byte[] bytes=new byte[buffer.remaining()];buffer.get(bytes);return bytes;}catch(CharacterCodingException e){throw new IOException("Invalid local UTF-8",e);}}
    private static void validateId(String id)throws IOException {if(id==null||!id.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"))throw new IOException("Invalid UUID");}
    private static void validateNickname(String name)throws IOException {if(name==null||name.trim().isEmpty()||name.codePointCount(0,name.length())>32||utf8(name).length>128||name.codePoints().anyMatch(Character::isISOControl))throw new IOException("Invalid nickname");}
    private static String hex(byte[] bytes){StringBuilder result=new StringBuilder(64);for(byte value:bytes)result.append(String.format(Locale.ROOT,"%02x",value&255));return result.toString();}
    private void ensureOpen()throws IOException {if(closed.get())throw new IOException("Channel closed");}
    public void close()throws IOException {
        if(!closed.compareAndSet(false,true))return;verified=false;
        try{connection.close();}finally{synchronized(readLock){synchronized(writeLock){Arrays.fill(noisePrivate,(byte)0);if(pair!=null){pair.destroy();pair=null;}}}}
    }
}
