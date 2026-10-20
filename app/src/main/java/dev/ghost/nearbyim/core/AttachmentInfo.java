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
    public final int version;
    public AttachmentInfo(String id, String name, String mime, long size, String hash, long time) throws IOException {
        this(id,name,mime,size,hash,time,1);
    }
    public static AttachmentInfo v2(String id,String name,String mime,long size,String hash,long time)throws IOException {
        return new AttachmentInfo(id,name,mime,size,hash,time,2);
    }
    private AttachmentInfo(String id,String name,String mime,long size,String hash,long time,int version)throws IOException {
        if (id == null || !id.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}") || name == null || name.trim().isEmpty()
                || name.codePointCount(0,name.length()) > 255 || encodeText(name).length > 1024
                || name.codePoints().anyMatch(Character::isISOControl) || mime == null || !mime.matches("[A-Za-z0-9!#$&^_.+/-]{1,127}")
                || size < 0 || size > (version==2?TransferLimits.MAX_FILE_BYTES:MAX_SIZE) || (hash==null?version!=2:!hash.matches("[0-9a-f]{64}")) || time < 0) throw new IOException("Invalid attachment metadata");
        this.version=version;this.id=id; String display=displayName(name); this.name=display.trim().isEmpty()?"attachment":display; this.mime=mime.toLowerCase(Locale.ROOT); this.size=size; this.hash=hash; this.time=time;
    }
    public Frame offer() throws IOException {
        if(version!=1)throw new IOException("Attachment v2 cannot use the legacy file offer");
        ByteArrayOutputStream bytes=new ByteArrayOutputStream(); DataOutputStream out=new DataOutputStream(bytes);
        text(out,name); text(out,mime); out.write(hex(hash));
        return new Frame(Frame.FILE_OFFER,id,"",time,size,bytes.toByteArray());
    }
    void writeV2(DataOutputStream out)throws IOException {
        if(version!=2)throw new IOException("Expected v2 metadata");
        text(out,id);text(out,name);text(out,mime);out.writeLong(size);out.writeLong(time);out.writeBoolean(hash!=null);if(hash!=null)out.write(hex(hash));
    }
    static AttachmentInfo readV2(DataInputStream in)throws IOException {
        String id=text(in,36),name=text(in,1024),mime=text(in,127);long size=in.readLong(),time=in.readLong();int hasHash=in.readUnsignedByte();
        if(hasHash>1)throw new IOException("Invalid digest presence");String hash=null;if(hasHash==1){byte[] bytes=new byte[32];in.readFully(bytes);hash=hex(bytes);}
        return v2(id,name,mime,size,hash,time);
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
        String s=displayName(value).replaceAll("[\\\\/\\p{Cntrl}:*?\"<>|]","_").replaceAll("[. ]+$","");
        if(s.trim().isEmpty()||s.equals(".")||s.equals("..")||s.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?"))s="attachment";
        return s;
    }
    /** Prevent remote direction controls from disguising a filename's extension. */
    public static String displayName(String value){return value.replaceAll("[\u061c\u200e\u200f\u202a-\u202e\u2066-\u2069]","");}
    /** External apps may interpret executable or unknown extensions as launch requests. */
    public boolean canOpenExternally(){
        String filename=safeName(name).toLowerCase(Locale.ROOT);int dot=filename.lastIndexOf('.');
        if(dot<0)return false;
        String extension=filename.substring(dot+1);
        return Arrays.asList("pdf","txt","csv","log","rtf","doc","docx","xls","xlsx","ppt","pptx","odt","ods","odp","epub","mp3","m4a","aac","flac","wav","ogg","mp4","m4v","mkv","webm","mov","avi","zip","7z","rar","gz","tar","bz2","xz").contains(extension);
    }
}
