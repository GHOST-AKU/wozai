package dev.ghost.nearbyim.core;

import java.io.*;
import java.nio.*;
import java.nio.charset.*;

/** NIM4 plaintext inside authenticated encryption. V1 file records are rejected. */
public final class ProtocolV4 {
    public static final int MAX_PLAINTEXT_BYTES=48*1024;
    private static final int MAGIC=0x4e494d34;
    private ProtocolV4(){}
    public static final class Record {
        public final Frame frame;public final TransferPacket transfer;public final AvatarPacket avatar;public final FileRoutePacket fileRoute;public final FileLinkPacket fileLink;
        private Record(Frame frame,TransferPacket transfer,AvatarPacket avatar){this.frame=frame;this.transfer=transfer;this.avatar=avatar;this.fileRoute=null;fileLink=null;}
        private Record(FileRoutePacket packet){frame=null;transfer=null;avatar=null;fileRoute=packet;fileLink=null;}
        private Record(FileLinkPacket packet){frame=null;transfer=null;avatar=null;fileRoute=null;fileLink=packet;}
    }
    public static byte[] encodeFileLink(FileLinkPacket packet)throws IOException{return encode(new Frame(Frame.FILE_LINK,"","",0,0,FileLinkCodec.encode(packet)));}
    public static byte[] encodeFileRoute(FileRoutePacket packet)throws IOException {return encode(new Frame(Frame.FILE_ROUTE,"","",0,0,FileRouteCodec.encode(packet)));}
    public static byte[] encodeAvatar(AvatarPacket packet)throws IOException {
        return encode(new Frame(Frame.AVATAR,"","",0,0,AvatarCodec.encode(packet)));
    }
    public static byte[] encodeTransfer(TransferPacket packet)throws IOException {
        if(packet==null)throw new IOException("Missing file packet");
        byte[] bytes=TransferCodec.encodeWithPrefix(packet,26);
        if(bytes.length>MAX_PLAINTEXT_BYTES)throw new IOException("NIM4 plaintext too large");
        ByteBuffer.wrap(bytes).putInt(MAGIC).put((byte)4).put((byte)Frame.ATTACHMENT_V2).putLong(0).putInt(0).putInt(0).putInt(bytes.length-26);
        return bytes;
    }
    /** Parse untrusted file packets once, before consent/generation checks in the session/engine. */
    public static Record decodeRecord(byte[] bytes)throws IOException {
        if(bytes==null||bytes.length<22||bytes.length>MAX_PLAINTEXT_BYTES)throw new IOException("Invalid NIM4 plaintext size");
        int type=bytes[5]&255;
        if(type==Frame.FILE_LINK){Frame frame=decode(bytes);return new Record(FileLinkCodec.decode(frame.data));}
        if(type==Frame.FILE_ROUTE){Frame frame=decode(bytes);return new Record(FileRouteCodec.decode(frame.data));}
        if(type==Frame.AVATAR){Frame frame=decode(bytes);return new Record(null,null,AvatarCodec.decode(frame.data));}
        if(type!=Frame.ATTACHMENT_V2)return new Record(decode(bytes),null,null);
        DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes));
        if(in.readInt()!=MAGIC||in.readUnsignedByte()!=4||in.readUnsignedByte()!=Frame.ATTACHMENT_V2||in.readLong()!=0||in.readInt()!=0||in.readInt()!=0)throw new IOException("Invalid file frame metadata");
        int length=in.readInt();if(length!=in.available()||length>TransferCodec.MAX_PACKET_BYTES)throw new IOException("Invalid file frame size");
        return new Record(null,TransferCodec.decode(bytes,bytes.length-length,length),null);
    }
    public static byte[] encode(Frame frame)throws IOException {
        validate(frame);ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
        out.writeInt(MAGIC);out.writeByte(4);out.writeByte(frame.type);out.writeLong(frame.timestamp);text(out,frame.id);text(out,frame.body);
        if(frame.type==Frame.ATTACHMENT_V2||frame.type==Frame.AVATAR||frame.type==Frame.FILE_ROUTE||frame.type==Frame.FILE_LINK){out.writeInt(frame.data.length);out.write(frame.data);}
        if(bytes.size()>MAX_PLAINTEXT_BYTES)throw new IOException("NIM4 plaintext too large");return bytes.toByteArray();
    }
    public static Frame decode(byte[] bytes)throws IOException {
        if(bytes==null||bytes.length<22||bytes.length>MAX_PLAINTEXT_BYTES)throw new IOException("Invalid NIM4 plaintext size");
        DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes));if(in.readInt()!=MAGIC||in.readUnsignedByte()!=4)throw new IOException("Unsupported NIM4 plaintext");
        int type=in.readUnsignedByte();long timestamp=in.readLong();String id=text(in,64),body=text(in,Protocol.MAX_TEXT_BYTES);byte[] data=new byte[0];
        if(type==Frame.ATTACHMENT_V2||type==Frame.AVATAR||type==Frame.FILE_ROUTE||type==Frame.FILE_LINK){int size=in.readInt();int maximum=type==Frame.FILE_LINK?FileLinkCodec.MAX_PACKET_BYTES:type==Frame.AVATAR?AvatarCodec.MAX_PACKET_BYTES:type==Frame.FILE_ROUTE?FileRouteCodec.MAX_PACKET_BYTES:TransferCodec.MAX_PACKET_BYTES;if(size<0||size>maximum||size>in.available())throw new IOException("Invalid binary packet size");data=new byte[size];in.readFully(data);}
        if(in.available()!=0)throw new IOException("Trailing NIM4 plaintext");Frame frame=new Frame(type,id,body,timestamp,0,data);validate(frame);return frame;
    }
    private static void validate(Frame frame)throws IOException {
        if(frame==null||frame.id==null||frame.body==null||frame.data==null||frame.timestamp<0||frame.offset!=0)throw new IOException("Invalid NIM4 frame");
        if(frame.type==Frame.ATTACHMENT_V2||frame.type==Frame.AVATAR||frame.type==Frame.FILE_ROUTE||frame.type==Frame.FILE_LINK) {
            int maximum=frame.type==Frame.FILE_LINK?FileLinkCodec.MAX_PACKET_BYTES:frame.type==Frame.AVATAR?AvatarCodec.MAX_PACKET_BYTES:frame.type==Frame.FILE_ROUTE?FileRouteCodec.MAX_PACKET_BYTES:TransferCodec.MAX_PACKET_BYTES;
            if(!frame.id.isEmpty()||!frame.body.isEmpty()||frame.timestamp!=0||frame.data.length>maximum)throw new IOException("Invalid binary frame metadata");
            if(frame.type==Frame.FILE_LINK)FileLinkCodec.decode(frame.data);else if(frame.type==Frame.AVATAR)AvatarCodec.decode(frame.data);else if(frame.type==Frame.FILE_ROUTE)FileRouteCodec.decode(frame.data);else TransferCodec.decode(frame.data,0,frame.data.length);
        }else {
            if(frame.type<Frame.READY||frame.type>Frame.PONG)throw new IOException("Unsupported NIM4 record type");
            Protocol.validate(frame);
        }
    }
    private static void text(DataOutputStream out,String value)throws IOException {
        try{ByteBuffer encoded=StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value));out.writeInt(encoded.remaining());byte[] bytes=new byte[encoded.remaining()];encoded.get(bytes);out.write(bytes);}
        catch(CharacterCodingException error){throw new IOException("Invalid NIM4 UTF-8",error);}
    }
    private static String text(DataInputStream in,int maximum)throws IOException {
        int size=in.readInt();if(size<0||size>maximum||size>in.available())throw new IOException("Invalid NIM4 string size");byte[] bytes=new byte[size];in.readFully(bytes);
        try{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}
        catch(CharacterCodingException error){throw new IOException("Invalid NIM4 UTF-8",error);}
    }
}
