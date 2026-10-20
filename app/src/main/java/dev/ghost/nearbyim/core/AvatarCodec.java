package dev.ghost.nearbyim.core;

import java.io.*;
import java.nio.*;
import java.nio.charset.*;

/** Strict, bounded v1 avatar binary codec inside an authenticated NIM4 record. */
public final class AvatarCodec {
    public static final int MAX_PACKET_BYTES=4608;
    private static final int MAGIC=0x41563131;
    private AvatarCodec(){}
    public static byte[] encode(AvatarPacket packet)throws IOException {
        if(packet==null)throw new IOException("Missing avatar packet");packet.validate();
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
        out.writeInt(0);out.writeInt(MAGIC);out.writeByte(1);out.writeByte(packet.kind.ordinal());out.writeLong(packet.revision);
        text(out,packet.avatarHash);text(out,packet.transferId);out.writeInt((int)packet.offset);out.writeByte(packet.code==null?255:packet.code.ordinal());
        out.writeBoolean(packet.profile!=null);
        if(packet.profile!=null){text(out,packet.profile.format);out.writeShort(packet.profile.width);out.writeShort(packet.profile.height);out.writeInt(packet.profile.byteLength);}
        out.writeInt(packet.data.length);out.write(packet.data);
        if(bytes.size()>MAX_PACKET_BYTES)throw new IOException("Avatar packet too large");byte[] result=bytes.toByteArray();ByteBuffer.wrap(result).putInt(result.length-4);return result;
    }
    public static void write(OutputStream output,AvatarPacket packet)throws IOException {output.write(encode(packet));}
    public static AvatarPacket read(InputStream input)throws IOException {
        DataInputStream in=new DataInputStream(input);int length=in.readInt();if(length<24||length>MAX_PACKET_BYTES-4)throw new IOException("Invalid avatar packet length");
        byte[] bytes=new byte[length+4];ByteBuffer.wrap(bytes).putInt(length);in.readFully(bytes,4,length);return decode(bytes);
    }
    public static AvatarPacket decode(byte[] bytes)throws IOException {return decode(bytes,0,bytes==null?0:bytes.length);}
    public static AvatarPacket decode(byte[] bytes,int offset,int count)throws IOException {
        if(bytes==null||offset<0||count<28||count>MAX_PACKET_BYTES||offset>bytes.length-count)throw new IOException("Invalid avatar packet range");
        DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes,offset,count));
        if(in.readInt()!=count-4||in.readInt()!=MAGIC||in.readUnsignedByte()!=1)throw new IOException("Unsupported avatar packet");
        int kind=in.readUnsignedByte();if(kind>=AvatarPacket.Kind.values().length)throw new IOException("Unknown avatar operation");
        long revision=in.readLong();String hash=text(in,64),id=text(in,36);long position=in.readInt();int code=in.readUnsignedByte();
        if(code!=255&&code>=AvatarPacket.Code.values().length)throw new IOException("Unknown avatar code");
        int present=in.readUnsignedByte();if(present>1)throw new IOException("Invalid avatar profile flag");AvatarProfile profile=null;
        if(present==1){String format=text(in,3);int width=in.readUnsignedShort(),height=in.readUnsignedShort(),length=in.readInt();
            try{profile=new AvatarProfile(revision,hash,format,width,height,length);}catch(IllegalArgumentException invalid){throw new IOException("Invalid avatar metadata",invalid);}}
        int length=in.readInt();if(length<0||length>AvatarPacket.CHUNK_BYTES||length>in.available())throw new IOException("Invalid avatar data length");
        byte[] data=new byte[length];in.readFully(data);if(in.available()!=0)throw new IOException("Trailing avatar data");
        return new AvatarPacket(AvatarPacket.Kind.values()[kind],profile,id,revision,hash,position,data,code==255?null:AvatarPacket.Code.values()[code]);
    }
    private static void text(DataOutputStream out,String value)throws IOException {
        try{ByteBuffer encoded=StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value));out.writeShort(encoded.remaining());byte[] bytes=new byte[encoded.remaining()];encoded.get(bytes);out.write(bytes);}
        catch(CharacterCodingException invalid){throw new IOException("Invalid avatar UTF-8",invalid);}
    }
    private static String text(DataInputStream in,int maximum)throws IOException {
        int length=in.readUnsignedShort();if(length>maximum||length>in.available())throw new IOException("Invalid avatar string length");byte[] bytes=new byte[length];in.readFully(bytes);
        try{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}
        catch(CharacterCodingException invalid){throw new IOException("Invalid avatar UTF-8",invalid);}
    }
}
