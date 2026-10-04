package dev.ghost.nearbyim.core;

import java.io.*;
import java.nio.charset.*;
import java.util.*;

/** Immutable, bounded metadata; names and MIME types are untrusted display hints. */
public final class AttachmentInfo {
    public static final long MAX_SIZE = 1L << 30;
    public static final int CHUNK_SIZE = 32768;
    public final String id, name, mime, hash;
    public final long size, time;
    public AttachmentInfo(String id, String name, String mime, long size, String hash, long time) throws IOException {
        if (id == null || !id.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}") || name == null || name.trim().isEmpty()
                || name.codePointCount(0,name.length()) > 255 || encodeText(name).length > 1024
                || name.codePoints().anyMatch(Character::isISOControl) || mime == null || !mime.matches("[A-Za-z0-9!#$&^_.+/-]{1,127}")
                || size < 0 || size > MAX_SIZE || hash == null || !hash.matches("[0-9a-f]{64}") || time < 0) throw new IOException("Invalid attachment metadata");
        this.id=id; this.name=name; this.mime=mime; this.size=size; this.hash=hash; this.time=time;
    }
    public Frame offer() throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream(); DataOutputStream out=new DataOutputStream(bytes);
        text(out,name); text(out,mime); out.write(hex(hash));
        return new Frame(Frame.FILE_OFFER,id,"",time,size,bytes.toByteArray());
    }
    public static AttachmentInfo from(Frame frame) throws IOException {
        if(frame.type!=Frame.FILE_OFFER || frame.data.length>1200)throw new IOException("Invalid file offer");
        DataInputStream in=new DataInputStream(new ByteArrayInputStream(frame.data));
        String name=text(in,1024), mime=text(in,127); byte[] hash=new byte[32]; in.readFully(hash);
        if(in.available()!=0)throw new IOException("Trailing attachment metadata");
        return new AttachmentInfo(frame.id,name,mime,frame.offset,hex(hash),frame.timestamp);
    }
    private static void text(DataOutputStream out,String value)throws IOException { byte[] b=encodeText(value);out.writeInt(b.length);out.write(b); }
    private static String text(DataInputStream in,int max)throws IOException {
        int n=in.readInt();if(n<1||n>max||n>in.available())throw new IOException("Invalid metadata length");
        byte[] b=new byte[n];in.readFully(b);
        try{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(b)).toString();}
        catch(CharacterCodingException e){throw new IOException("Invalid metadata UTF8",e);}
    }
    private static byte[] encodeText(String s)throws IOException {
        try { java.nio.ByteBuffer b=StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(s));byte[] r=new byte[b.remaining()];b.get(r);return r; }
        catch(CharacterCodingException e){throw new IOException("Invalid metadata Unicode",e);}
    }
    public static String hex(byte[] bytes) { StringBuilder s=new StringBuilder(bytes.length*2);for(byte b:bytes)s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString(); }
    private static byte[] hex(String value){byte[] b=new byte[value.length()/2];for(int i=0;i<b.length;i++)b[i]=(byte)Integer.parseInt(value.substring(i*2,i*2+2),16);return b;}
    public static String safeName(String value) {
        String s=value.replaceAll("[\\\\/\\p{Cntrl}:*?\"<>|]","_").replaceAll("[. ]+$","");
        if(s.trim().isEmpty()||s.equals(".")||s.equals("..")||s.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?"))s="attachment";
        return s;
    }
}
