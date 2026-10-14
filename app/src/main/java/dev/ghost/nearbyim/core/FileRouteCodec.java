package dev.ghost.nearbyim.core;
import java.io.*;
import java.util.*;
/** Small bounded ASCII codec; no DNS resolution or extensible untrusted allocation. */
public final class FileRouteCodec {
    public static final int MAX_PACKET_BYTES=2048;
    private FileRouteCodec(){}
    public static byte[] encode(FileRoutePacket p)throws IOException {
        if(p==null)throw new IOException("Missing FILE control");ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
        out.writeByte(1);out.writeByte(p.kind.ordinal());text(out,p.nonce);text(out,p.generation);out.writeByte(p.route.ordinal());out.writeBoolean(p.terminal);out.writeByte(p.endpoints.size());
        for(AutomaticFileRoutes.Endpoint e:p.endpoints){text(out,e.host);out.writeShort(e.port);}if(bytes.size()>MAX_PACKET_BYTES)throw new IOException("FILE control too large");return bytes.toByteArray();
    }
    private static void text(DataOutputStream out,String text)throws IOException {
        byte[] bytes=text.getBytes(java.nio.charset.StandardCharsets.US_ASCII);if(!text.equals(new String(bytes,java.nio.charset.StandardCharsets.US_ASCII))||bytes.length>64)throw new IOException("Invalid FILE text");out.writeByte(bytes.length);out.write(bytes);
    }
    private static String text(DataInputStream in,int maximum)throws IOException {
        int size=in.readUnsignedByte();if(size>maximum||size>in.available())throw new IOException("Invalid FILE text length");byte[] bytes=new byte[size];in.readFully(bytes);for(byte b:bytes)if(b<0)throw new IOException("Invalid FILE ASCII");return new String(bytes,java.nio.charset.StandardCharsets.US_ASCII);
    }
    public static FileRoutePacket decode(byte[] bytes)throws IOException {
        if(bytes==null||bytes.length>MAX_PACKET_BYTES)throw new IOException("Invalid FILE control size");DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes));
        try{if(in.readUnsignedByte()!=1)throw new IOException("Unknown FILE control version");int k=in.readUnsignedByte();String nonce=text(in,36),generation=text(in,64);int r=in.readUnsignedByte(),flag=in.readUnsignedByte(),n=in.readUnsignedByte();
            if(k>=FileRoutePacket.Kind.values().length||r>=AutomaticFileRoutes.Route.values().length||flag>1||n>8)throw new IOException("Invalid FILE control fields");
            List<AutomaticFileRoutes.Endpoint> endpoints=new ArrayList<>(n);for(int i=0;i<n;i++)endpoints.add(new AutomaticFileRoutes.Endpoint(text(in,64),in.readUnsignedShort()));
            if(in.available()!=0)throw new IOException("Trailing FILE control");return new FileRoutePacket(FileRoutePacket.Kind.values()[k],nonce,generation,AutomaticFileRoutes.Route.values()[r],flag==1,endpoints);
        }catch(IllegalArgumentException e){throw new IOException("Invalid FILE control",e);}
    }
}
