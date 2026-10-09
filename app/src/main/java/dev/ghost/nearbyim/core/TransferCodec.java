package dev.ghost.nearbyim.core;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;

/** Strict self-delimiting packets, independently usable inside authenticated records. */
public final class TransferCodec {
    public static final int MAX_PACKET_BYTES=40*1024;
    private static final int MAGIC=0x46543032;
    private TransferCodec(){}
    public static void write(OutputStream output,TransferPacket packet)throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream payload=new DataOutputStream(bytes);
        payload.writeInt(MAGIC);payload.writeByte(packet.kind.ordinal());payload.writeBoolean(packet.fromSender);
        text(payload,packet.transferId);text(payload,packet.sourceGeneration);text(payload,packet.connectionGeneration);
        payload.writeLong(packet.totalSize);payload.writeLong(packet.offset);payload.writeLong(packet.windowBytes);text(payload,packet.reason);
        payload.writeBoolean(packet.info!=null);if(packet.info!=null)packet.info.writeV2(payload);
        payload.writeBoolean(packet.hash!=null);if(packet.hash!=null)payload.write(packet.hash);
        payload.writeInt(packet.data.length);payload.write(packet.data);
        if(bytes.size()+4>MAX_PACKET_BYTES)throw new IOException("Transfer packet too large");
        DataOutputStream out=new DataOutputStream(output);out.writeInt(bytes.size());bytes.writeTo(out);
    }
    public static TransferPacket read(InputStream input)throws IOException {
        DataInputStream wire=new DataInputStream(input);int length=wire.readInt();
        if(length<32||length>MAX_PACKET_BYTES-4)throw new IOException("Invalid transfer packet length");
        byte[] bytes=new byte[length];wire.readFully(bytes);DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes));
        if(in.readInt()!=MAGIC)throw new IOException("Unsupported transfer codec");int code=in.readUnsignedByte();
        if(code>=TransferPacket.Kind.values().length)throw new IOException("Unknown transfer packet type");boolean sender=flag(in);
        String id=text(in,36),generation=text(in,36),connection=text(in,128);long size=in.readLong(),offset=in.readLong(),window=in.readLong();String reason=text(in,16);
        AttachmentInfo info=flag(in)?AttachmentInfo.readV2(in):null;byte[] hash=null;if(flag(in)){hash=new byte[32];in.readFully(hash);}
        int n=in.readInt();if(n<0||n>TransferLimits.DATA_BYTES||n>in.available())throw new IOException("Invalid transfer data length");
        byte[] data=new byte[n];in.readFully(data);if(in.available()!=0)throw new IOException("Trailing transfer packet data");
        return new TransferPacket(TransferPacket.Kind.values()[code],id,generation,connection,sender,size,offset,window,data,hash,info,reason);
    }
    private static boolean flag(DataInputStream in)throws IOException {int value=in.readUnsignedByte();if(value>1)throw new IOException("Invalid boolean field");return value==1;}
    private static void text(DataOutputStream out,String value)throws IOException {byte[] bytes=value.getBytes(StandardCharsets.UTF_8);out.writeInt(bytes.length);out.write(bytes);}
    private static String text(DataInputStream in,int max)throws IOException {
        int n=in.readInt();if(n<0||n>max||n>in.available())throw new IOException("Invalid transfer string length");byte[] bytes=new byte[n];in.readFully(bytes);
        try{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}
        catch(CharacterCodingException error){throw new IOException("Invalid transfer UTF-8",error);}
    }
}
