package dev.ghost.nearbyim.core;

import java.io.*;
import java.util.*;

/** Fixed envelopes bound allocation before reading any variable-length payload. */
public final class FileLinkCodec {
    public static final int MAX_PACKET_BYTES=TransferLimits.DATA_BYTES+1024;
    private FileLinkCodec(){}
    public static byte[] encode(FileLinkPacket packet)throws IOException {
        if(packet==null)throw new IOException("Missing FILE link packet");
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
        out.writeByte(1);out.writeByte(packet.kind.ordinal());out.writeUTF(packet.nonce);out.writeUTF(packet.generation);out.writeByte(packet.number);out.writeLong(packet.value);out.writeLong(packet.nanos);
        out.writeByte(packet.endpoints.size());for(AutomaticFileRoutes.Endpoint e:packet.endpoints){out.writeUTF(e.host);out.writeShort(e.port);}
        out.writeInt(packet.data.length);out.write(packet.data);if(bytes.size()>MAX_PACKET_BYTES)throw new IOException("FILE link record too large");return bytes.toByteArray();
    }
    public static FileLinkPacket decode(byte[] bytes)throws IOException {
        if(bytes==null||bytes.length>MAX_PACKET_BYTES)throw new IOException("Invalid FILE link record size");
        DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes));
        try{
            if(in.readUnsignedByte()!=1)throw new IOException("Unsupported FILE link record");int kind=in.readUnsignedByte();
            if(kind>=FileLinkPacket.Kind.values().length)throw new IOException("Invalid FILE link kind");
            String nonce=text(in,36),generation=text(in,64);int number=in.readUnsignedByte();long value=in.readLong(),nanos=in.readLong();
            int count=in.readUnsignedByte();if(count>8)throw new IOException("Too many FILE link endpoints");
            List<AutomaticFileRoutes.Endpoint> endpoints=new ArrayList<>(count);for(int i=0;i<count;i++)endpoints.add(new AutomaticFileRoutes.Endpoint(text(in,64),in.readUnsignedShort()));
            int length=in.readInt();if(length<0||length>TransferLimits.DATA_BYTES||length!=in.available())throw new IOException("Invalid FILE link payload length");
            byte[] data=new byte[length];in.readFully(data);
            return new FileLinkPacket(FileLinkPacket.Kind.values()[kind],nonce,generation,number,value,nanos,data,endpoints);
        }catch(IllegalArgumentException error){throw new IOException("Invalid FILE link record",error);}
    }
    private static String text(DataInputStream in,int maximum)throws IOException {
        int length=in.readUnsignedShort();if(length>maximum||length>in.available())throw new IOException("Invalid FILE link text");
        byte[] bytes=new byte[length];in.readFully(bytes);for(byte value:bytes)if(value<32||value>126)throw new IOException("Invalid FILE link ASCII");return new String(bytes,java.nio.charset.StandardCharsets.US_ASCII);
    }
}
