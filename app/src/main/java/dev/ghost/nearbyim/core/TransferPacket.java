package dev.ghost.nearbyim.core;

import java.io.IOException;
import java.util.Set;
import java.util.regex.Pattern;

/** Direction is relative to the original file sender, not the current connection endpoint. */
public final class TransferPacket {
    private static final Pattern UUID=Pattern.compile("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}");
    private static final Pattern CONNECTION=Pattern.compile("([0-9a-f]{64}|[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12})");
    private static final Set<String> REASONS=Set.of("","canceled","failed","busy","paused","rejected","tooLarge");
    public enum Kind { OFFER, ACCEPT, DATA, BLOCK_HASH, CREDIT, END, COMPLETE, CANCEL, RESUME, STATUS, PAUSE }
    public final Kind kind;
    public final String transferId,sourceGeneration,connectionGeneration,reason;
    public final boolean fromSender;
    public final long totalSize,offset,windowBytes,verifiedOffset,durableOffset;
    public final byte[] data,hash;
    public final AttachmentInfo info;

    TransferPacket(Kind kind,String id,String sourceGeneration,String connectionGeneration,boolean fromSender,long totalSize,long offset,long windowBytes,byte[] data,byte[] hash,AttachmentInfo info,String reason)throws IOException {
        this(kind,id,sourceGeneration,connectionGeneration,fromSender,totalSize,offset,windowBytes,0,0,data,hash,info,reason);
    }
    TransferPacket(Kind kind,String id,String sourceGeneration,String connectionGeneration,boolean fromSender,long totalSize,long offset,long windowBytes,long verifiedOffset,long durableOffset,byte[] data,byte[] hash,AttachmentInfo info,String reason)throws IOException {
        if(kind==null||!uuid(id)||!uuid(sourceGeneration)||connectionGeneration==null||!CONNECTION.matcher(connectionGeneration).matches()
                ||data==null||data.length>TransferLimits.DATA_BYTES||reason==null||!REASONS.contains(reason))throw new IOException("Invalid transfer packet metadata");
        TransferLimits.validateRange(totalSize,offset,data.length);
        if(windowBytes<0||windowBytes>8L*1024*1024||hash!=null&&hash.length!=32)throw new IOException("Invalid credit or hash");
        if(durableOffset<0||verifiedOffset<durableOffset||verifiedOffset>offset
                ||kind!=Kind.CREDIT&&kind!=Kind.RESUME&&(verifiedOffset!=0||durableOffset!=0)
                ||verifiedOffset%TransferLimits.BLOCK_BYTES!=0&&verifiedOffset!=totalSize
                ||durableOffset%TransferLimits.BLOCK_BYTES!=0&&durableOffset!=totalSize)throw new IOException("Invalid durable progress");
        boolean digest=kind==Kind.BLOCK_HASH||kind==Kind.END||kind==Kind.COMPLETE||kind==Kind.RESUME;
        if(digest!=(hash!=null)||kind!=Kind.DATA&&data.length!=0||kind==Kind.DATA&&data.length==0
                ||(kind==Kind.OFFER)!=(info!=null)||info!=null&&(info.version!=2||!id.equals(info.id)||info.size!=totalSize||info.hash!=null)
                ||kind!=Kind.CANCEL&&!reason.isEmpty()||kind!=Kind.ACCEPT&&kind!=Kind.CREDIT&&kind!=Kind.RESUME&&windowBytes!=0
                ||kind==Kind.OFFER&&offset!=0||kind==Kind.END&&offset!=totalSize||kind==Kind.COMPLETE&&offset!=totalSize
                ||(kind==Kind.OFFER||kind==Kind.DATA||kind==Kind.BLOCK_HASH||kind==Kind.END)&&!fromSender
                ||(kind==Kind.ACCEPT||kind==Kind.CREDIT||kind==Kind.COMPLETE)&&fromSender)
            throw new IOException("Invalid transfer packet fields");
        this.kind=kind;transferId=id;this.sourceGeneration=sourceGeneration;this.connectionGeneration=connectionGeneration;this.fromSender=fromSender;
        this.totalSize=totalSize;this.offset=offset;this.windowBytes=windowBytes;this.verifiedOffset=verifiedOffset;this.durableOffset=durableOffset;this.data=data.clone();this.hash=hash==null?null:hash.clone();this.info=info;this.reason=reason;
    }
    private static boolean uuid(String value){return value!=null&&UUID.matcher(value).matches();}
    public static TransferPacket offer(AttachmentInfo info,String generation,String connection)throws IOException {
        return new TransferPacket(Kind.OFFER,info.id,generation,connection,true,info.size,0,0,new byte[0],null,info,"");
    }
    public static TransferPacket data(String id,String generation,String connection,long size,long offset,byte[] bytes)throws IOException {
        return new TransferPacket(Kind.DATA,id,generation,connection,true,size,offset,0,bytes,null,null,"");
    }
    public static TransferPacket control(Kind kind,String id,String generation,String connection,boolean fromSender,long size,long offset,long window,byte[] hash,String reason)throws IOException {
        return new TransferPacket(kind,id,generation,connection,fromSender,size,offset,window,new byte[0],hash,null,reason);
    }
    public static TransferPacket progress(Kind kind,String id,String generation,String connection,long size,long written,long verified,long durable,long window,byte[] hash)throws IOException {
        if(kind!=Kind.CREDIT&&kind!=Kind.RESUME)throw new IOException("Not a progress packet");
        return new TransferPacket(kind,id,generation,connection,false,size,written,window,verified,durable,new byte[0],hash,null,"");
    }
}
