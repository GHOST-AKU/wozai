package dev.ghost.nearbyim.noise;

import com.southernstorm.noise.protocol.CipherState;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;

/** Noise CipherState adapter to system AES/GCM; contains no AES or GHASH implementation. */
public final class JcaAesGcmCipherState implements CipherState {
    private Cipher cipher;
    private byte[] key;
    private final byte[] iv=new byte[12];
    private long nonce;
    private boolean destroyed;
    public JcaAesGcmCipherState()throws NoSuchAlgorithmException {cipher=create();}
    private static Cipher create()throws NoSuchAlgorithmException {
        try{return Cipher.getInstance("AES/GCM/NoPadding");}catch(NoSuchPaddingException error){throw new NoSuchAlgorithmException("System AES/GCM unavailable",error);}
    }
    private void alive(){if(destroyed)throw new IllegalStateException("Cipher destroyed");}
    public String getCipherName(){return "AESGCM";}
    public int getKeyLength(){return 32;}
    public int getMACLength(){return hasKey()?16:0;}
    public boolean hasKey(){return key!=null&&!destroyed;}
    public void initializeKey(byte[] value,int offset) {
        alive();if(value==null||offset<0||offset>value.length-32)throw new IllegalArgumentException("Invalid AES key range");
        if(key!=null)Arrays.fill(key,(byte)0);key=Arrays.copyOfRange(value,offset,offset+32);nonce=0;
        // A fresh provider context also avoids retaining a previously initialized key/IV.
        try{cipher=create();}catch(NoSuchAlgorithmException error){destroy();throw new IllegalStateException(error);}
    }
    private static void range(byte[] bytes,int offset,int length) {if(bytes==null||offset<0||length<0||offset>bytes.length-length)throw new IllegalArgumentException("Invalid cipher range");}
    private void initialize(int mode,byte[] ad)throws GeneralSecurityException {
        alive();if(nonce==-1)throw new IllegalStateException("Noise nonce exhausted");
        Arrays.fill(iv,0,4,(byte)0);for(int i=0;i<8;i++)iv[4+i]=(byte)(nonce>>>(56-8*i));
        cipher.init(mode,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,iv));if(ad!=null&&ad.length!=0)cipher.updateAAD(ad);
    }
    public int encryptWithAd(byte[] ad,byte[] input,int from,byte[] output,int to,int length)throws ShortBufferException {
        alive();range(input,from,length);int size=length+getMACLength();if(length>65535-getMACLength())throw new IllegalArgumentException("Noise ciphertext exceeds record limit");
        if(output==null||to<0||to>output.length-size)throw new ShortBufferException();
        if(!hasKey()){System.arraycopy(input,from,output,to,length);return length;}
        try{initialize(Cipher.ENCRYPT_MODE,ad);int n=cipher.doFinal(input,from,length,output,to);nonce++;return n;}
        catch(ShortBufferException error){throw error;}
        catch(GeneralSecurityException error){throw new IllegalStateException("System AEAD encryption failed",error);}
    }
    public int decryptWithAd(byte[] ad,byte[] input,int from,byte[] output,int to,int length)throws ShortBufferException,BadPaddingException {
        alive();range(input,from,length);if(length>65535)throw new IllegalArgumentException("Noise ciphertext exceeds record limit");
        int size=length-getMACLength();if(size<0)throw new BadPaddingException("Truncated authentication tag");
        if(output==null||to<0||to>output.length-size)throw new ShortBufferException();
        if(!hasKey()){System.arraycopy(input,from,output,to,length);return length;}
        try{initialize(Cipher.DECRYPT_MODE,ad);int n=cipher.doFinal(input,from,length,output,to);nonce++;return n;}
        catch(ShortBufferException error){throw error;}
        catch(BadPaddingException error){Arrays.fill(output,to,to+size,(byte)0);throw error;}
        catch(GeneralSecurityException error){Arrays.fill(output,to,to+size,(byte)0);throw new IllegalStateException("System AEAD decryption failed",error);}
    }
    public CipherState fork(byte[] value,int offset) {alive();try{JcaAesGcmCipherState next=new JcaAesGcmCipherState();next.initializeKey(value,offset);return next;}catch(NoSuchAlgorithmException error){throw new IllegalStateException(error);}}
    public void setNonce(long value){alive();nonce=value;}
    public void destroy(){if(destroyed)return;destroyed=true;if(key!=null){Arrays.fill(key,(byte)0);key=null;}Arrays.fill(iv,(byte)0);nonce=-1;cipher=null;}
}
