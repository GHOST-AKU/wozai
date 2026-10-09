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
        public final Frame frame;public final TransferPacket transfer;
        private Record(Frame frame,TransferPacket transfer){this.frame=frame;this.transfer=transfer;}
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
        if((bytes[5]&255)!=Frame.ATTACHMENT_V2)return new Record(decode(bytes),null);
        DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes));
        if(in.readInt()!=MAGIC||in.readUnsignedByte()!=4||in.readUnsignedByte()!=Frame.ATTACHMENT_V2||in.readLong()!=0||in.readInt()!=0||in.readInt()!=0)throw new IOException("Invalid file frame metadata");
        int length=in.readInt();if(length!=in.available()||length>TransferCodec.MAX_PACKET_BYTES)throw new IOException("Invalid file frame size");
        return new Record(null,TransferCodec.decode(bytes,bytes.length-length,length));
    }
    public static byte[] encode(Frame frame)throws IOException {
        validate(frame);ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
        out.writeInt(MAGIC);out.writeByte(4);out.writeByte(frame.type);out.writeLong(frame.timestamp);text(out,frame.id);text(out,frame.body);
        if(frame.type==Frame.ATTACHMENT_V2){out.writeInt(frame.data.length);out.write(frame.data);}
        if(bytes.size()>MAX_PLAINTEXT_BYTES)throw new IOException("NIM4 plaintext too large");return bytes.toByteArray();
    }
    public static Frame decode(byte[] bytes)throws IOException {
        if(bytes==null||bytes.length<22||bytes.length>MAX_PLAINTEXT_BYTES)throw new IOException("Invalid NIM4 plaintext size");
        DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes));if(in.readInt()!=MAGIC||in.readUnsignedByte()!=4)throw new IOException("Unsupported NIM4 plaintext");
        int type=in.readUnsignedByte();long timestamp=in.readLong();String id=text(in,64),body=text(in,Protocol.MAX_TEXT_BYTES);byte[] data=new byte[0];
        if(type==Frame.ATTACHMENT_V2){int size=in.readInt();if(size<0||size>TransferCodec.MAX_PACKET_BYTES||size>in.available())throw new IOException("Invalid file packet size");data=new byte[size];in.readFully(data);}
        if(in.available()!=0)throw new IOException("Trailing NIM4 plaintext");Frame frame=new Frame(type,id,body,timestamp,0,data);validate(frame);return frame;
    }
    private static void validate(Frame frame)throws IOException {
        if(frame==null||frame.id==null||frame.body==null||frame.data==null||frame.timestamp<0||frame.offset!=0)throw new IOException("Invalid NIM4 frame");
        if(frame.type==Frame.ATTACHMENT_V2) {
            if(!frame.id.isEmpty()||!frame.body.isEmpty()||frame.timestamp!=0||frame.data.length>TransferCodec.MAX_PACKET_BYTES)throw new IOException("Invalid file frame metadata");
            TransferCodec.decode(frame.data,0,frame.data.length);
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
