package dev.ghost.nearbyim.core;

import java.io.*;
import java.util.*;

/** A bounded, negotiated NIM4 extension. No reading timestamp or history watermark. */
public final class ReadReceiptPacket {
    public static final int POLICY=0, READ=1, ACK=2, MAX_IDS=32, MAX_BYTES=19+16*MAX_IDS;
    public final int kind;
    public final long epoch, peerEpoch;
    public final boolean enabled;
    public final List<String> ids;

    private ReadReceiptPacket(int kind,long epoch,long peerEpoch,boolean enabled,List<String> ids) {
        if(epoch<1||peerEpoch<0||kind<POLICY||kind>ACK||ids==null)throw new IllegalArgumentException("Invalid read policy");
        if(kind==POLICY?(peerEpoch!=0||!ids.isEmpty()):(peerEpoch<1||ids.isEmpty()||ids.size()>MAX_IDS))throw new IllegalArgumentException("Invalid read receipt");
        List<String> canonical=new ArrayList<>();
        for(String id:ids){if(id==null||!id.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")||canonical.contains(id))throw new IllegalArgumentException("Invalid read message ID");canonical.add(id);}
        this.kind=kind;this.epoch=epoch;this.peerEpoch=peerEpoch;this.enabled=enabled;this.ids=Collections.unmodifiableList(canonical);
    }
    public static ReadReceiptPacket policy(long epoch,boolean enabled){return new ReadReceiptPacket(POLICY,epoch,0,enabled,List.of());}
    public static ReadReceiptPacket receipt(int kind,long epoch,long peerEpoch,List<String> ids){if(kind==POLICY)throw new IllegalArgumentException("Missing read IDs");return new ReadReceiptPacket(kind,epoch,peerEpoch,false,ids);}
    public byte[] encode()throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
        out.writeByte(1);out.writeByte(kind);out.writeLong(epoch);out.writeLong(peerEpoch);
        out.writeByte(kind==POLICY?(enabled?1:0):ids.size());
        for(String id:ids){UUID uuid=UUID.fromString(id);out.writeLong(uuid.getMostSignificantBits());out.writeLong(uuid.getLeastSignificantBits());}
        return bytes.toByteArray();
    }
    public static ReadReceiptPacket decode(byte[] bytes)throws IOException {
        if(bytes==null||bytes.length<19||bytes.length>MAX_BYTES)throw new IOException("Invalid read receipt size");
        DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes));
        if(in.readUnsignedByte()!=1)throw new IOException("Unsupported read receipt version");
        int kind=in.readUnsignedByte();long epoch=in.readLong(),peer=in.readLong();int count=in.readUnsignedByte();
        if(kind==POLICY?count>1:count<1||count>MAX_IDS)throw new IOException("Invalid read receipt count");
        List<String> ids=new ArrayList<>();
        if(kind!=POLICY)for(int i=0;i<count;i++)ids.add(new UUID(in.readLong(),in.readLong()).toString());
        if(in.available()!=0)throw new IOException("Trailing read receipt data");
        try{return kind==POLICY?policy(epoch,count==1):receipt(kind,epoch,peer,ids);}catch(IllegalArgumentException invalid){throw new IOException("Invalid read receipt",invalid);}
    }
}
