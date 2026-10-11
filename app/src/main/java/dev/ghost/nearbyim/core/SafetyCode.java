package dev.ghost.nearbyim.core;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Full, versioned comparison of two persistent root identities, not session keys. */
public final class SafetyCode {
    private static final String PREFIX="WOZAI-ID1:";
    private final String hex;
    public SafetyCode(String localRoot,String remoteRoot)throws GeneralSecurityException {
        byte[] a=decode(localRoot),b=decode(remoteRoot);
        if(compare(a,b)>0){byte[] swap=a;a=b;b=swap;}
        MessageDigest hash=MessageDigest.getInstance("SHA-256");
        hash.update("wozai/identity-verification/v1\0".getBytes(StandardCharsets.US_ASCII));
        add(hash,a);add(hash,b);
        StringBuilder encoded=new StringBuilder(64);
        for(byte value:hash.digest())encoded.append("0123456789abcdef".charAt((value&255)>>>4)).append("0123456789abcdef".charAt(value&15));
        hex=encoded.toString();
    }
    private static byte[] decode(String root)throws GeneralSecurityException {
        if(root==null||root.length()>344)throw new InvalidKeyException("Invalid root size");
        byte[] bytes;try{bytes=Base64.getDecoder().decode(root);}catch(IllegalArgumentException error){throw new InvalidKeyException("Invalid root encoding",error);}
        if(!Base64.getEncoder().encodeToString(bytes).equals(root))throw new InvalidKeyException("Noncanonical root encoding");
        DeviceIdentity.decodePublicKey(bytes);return bytes;
    }
    private static int compare(byte[] a,byte[] b){for(int i=0;i<Math.min(a.length,b.length);i++){int difference=(a[i]&255)-(b[i]&255);if(difference!=0)return difference;}return a.length-b.length;}
    private static void add(MessageDigest hash,byte[] root){int n=root.length;hash.update(new byte[]{(byte)(n>>>24),(byte)(n>>>16),(byte)(n>>>8),(byte)n});hash.update(root);}
    public String hex(){return hex;}
    public String display(){StringBuilder text=new StringBuilder(72);for(int i=0;i<64;i+=8){if(i!=0)text.append(i%32==0?'\n':' ');text.append(hex,i,i+8);}return text.toString();}
    public String qrPayload(){return PREFIX+hex;}
    public boolean matchesQr(String input){
        if(input==null||input.length()!=PREFIX.length()+64||!input.startsWith(PREFIX))return false;
        return MessageDigest.isEqual(qrPayload().getBytes(StandardCharsets.US_ASCII),input.getBytes(StandardCharsets.US_ASCII));
    }
}
