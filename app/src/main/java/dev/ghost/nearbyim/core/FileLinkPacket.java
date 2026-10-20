package dev.ghost.nearbyim.core;

import java.util.*;

/** Parent-bound migration controls and bounded link samples, never user attachments. */
public final class FileLinkPacket {
    public enum Kind {REQUEST,PREPARE,ENDPOINTS,BIND,BOUND,PAUSE,PAUSED,SELECT,SELECTED,RESUME,ABORT,FAILED,PROBE_START,PROBE_DATA,PROBE_END,PROBE_ACK,PROGRESS,RECOVERY_OFFER,PROBE_DONE,PROBE_RESULT,MEASURE,STANDBY_ENDPOINTS,INTERRUPTED}
    public final Kind kind;
    public final String nonce,generation;
    public final int number;
    public final long value,nanos;
    public final byte[] data;
    public final List<AutomaticFileRoutes.Endpoint> endpoints;
    FileLinkPacket(Kind kind,String nonce,String generation,int number,long value,long nanos,byte[] data,List<AutomaticFileRoutes.Endpoint> endpoints){
        this.kind=Objects.requireNonNull(kind);this.nonce=Objects.requireNonNull(nonce);this.generation=Objects.requireNonNull(generation);
        if(!nonce.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")||!generation.matches("[0-9a-f]{64}")||number<0||number>3||value<0||nanos<0)throw new IllegalArgumentException("Invalid FILE link binding");
        this.number=number;this.value=value;this.nanos=nanos;this.data=Objects.requireNonNull(data).clone();
        this.endpoints=Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNull(endpoints)));
        if(this.endpoints.size()>8||this.endpoints.contains(null)||kind!=Kind.ENDPOINTS&&kind!=Kind.STANDBY_ENDPOINTS&&!this.endpoints.isEmpty())throw new IllegalArgumentException("Invalid FILE link endpoints");
        boolean probe=kind==Kind.PROBE_START||kind==Kind.PROBE_DATA||kind==Kind.PROBE_END||kind==Kind.PROBE_ACK||kind==Kind.PROBE_DONE;
        if(kind==Kind.PROBE_DATA){if(data.length<1||data.length>TransferLimits.DATA_BYTES||value>FileRouteQualityPolicy.ROUND_BYTES-data.length||nanos!=0)throw new IllegalArgumentException("Invalid probe data");}
        else if(kind==Kind.PREPARE){if(data.length!=36||!new String(data,java.nio.charset.StandardCharsets.US_ASCII).matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"))throw new IllegalArgumentException("Invalid origin FILE lane");}
        else if(kind==Kind.PROGRESS){if(data.length!=16)throw new IllegalArgumentException("Invalid activity limits");}
        else if(kind==Kind.RECOVERY_OFFER){
            if(data.length>4096)throw new IllegalArgumentException("Recovery offer too large");
            try{TransferPacket offer=TransferCodec.decode(data,0,data.length);if(offer.kind!=TransferPacket.Kind.OFFER||!generation.equals(offer.connectionGeneration))throw new IllegalArgumentException("Invalid recovery offer binding");}catch(java.io.IOException invalid){throw new IllegalArgumentException("Invalid recovery offer",invalid);}
        }else if(data.length!=0)throw new IllegalArgumentException("Unexpected FILE link payload");
        if(probe&&(value>FileRouteQualityPolicy.ROUND_BYTES||nanos!=0))throw new IllegalArgumentException("Invalid probe counter");
        if(kind==Kind.PROBE_RESULT){if(value>FileRouteQualityPolicy.ROUND_BYTES||nanos>FileRouteQualityPolicy.STALL_NS)throw new IllegalArgumentException("Invalid probe result");}
        else if(kind==Kind.PROGRESS){if(value>2L*TransferLimits.MAX_FILE_BYTES||nanos>2L*TransferLimits.MAX_FILE_BYTES)throw new IllegalArgumentException("Invalid transfer activity");}
        else if(!probe&&(value!=0||nanos!=0))throw new IllegalArgumentException("Unexpected link counters");
        if(!probe&&kind!=Kind.PROGRESS&&kind!=Kind.PROBE_RESULT&&kind!=Kind.PREPARE&&kind!=Kind.REQUEST&&number!=0)throw new IllegalArgumentException("Unexpected link flags");
    }
    public static FileLinkPacket control(Kind kind,String nonce,String generation,int number){return new FileLinkPacket(kind,nonce,generation,number,0,0,new byte[0],List.of());}
    public static FileLinkPacket prepare(String nonce,String generation,int flags,String origin){return new FileLinkPacket(Kind.PREPARE,nonce,generation,flags,0,0,origin.getBytes(java.nio.charset.StandardCharsets.US_ASCII),List.of());}
    public static FileLinkPacket endpoints(String nonce,String generation,List<AutomaticFileRoutes.Endpoint> endpoints){return new FileLinkPacket(Kind.ENDPOINTS,nonce,generation,0,0,0,new byte[0],endpoints);}
    public static FileLinkPacket standby(String nonce,String generation,List<AutomaticFileRoutes.Endpoint> endpoints){return new FileLinkPacket(Kind.STANDBY_ENDPOINTS,nonce,generation,0,0,0,new byte[0],endpoints);}
    public static FileLinkPacket data(String nonce,String generation,int sample,long offset,byte[] bytes){return new FileLinkPacket(Kind.PROBE_DATA,nonce,generation,sample,offset,0,bytes,List.of());}
    public static FileLinkPacket probe(Kind kind,String nonce,String generation,int sample,long bytes){return new FileLinkPacket(kind,nonce,generation,sample,bytes,0,new byte[0],List.of());}
    public static FileLinkPacket progress(String nonce,String generation,int phase,long remaining,long rate){return new FileLinkPacket(Kind.PROGRESS,nonce,generation,phase,remaining,rate,new byte[16],List.of());}
    public static FileLinkPacket activity(String nonce,String generation,FileTransferActivity activity){byte[] limits=java.nio.ByteBuffer.allocate(16).putLong(activity.capacity).putLong(Math.min(FileRouteQualityPolicy.CACHE_NS,activity.switchCost)).array();return new FileLinkPacket(Kind.PROGRESS,nonce,generation,activity.busy?2:!activity.active?3:activity.sending?1:0,activity.remaining,activity.rate,limits,List.of());}
    public static FileLinkPacket result(String nonce,String generation,int sample,long bytes,long nanos){return new FileLinkPacket(Kind.PROBE_RESULT,nonce,generation,sample,bytes,nanos,new byte[0],List.of());}
    public static FileLinkPacket recoveryOffer(String nonce,String generation,TransferPacket offer)throws java.io.IOException{return new FileLinkPacket(Kind.RECOVERY_OFFER,nonce,generation,0,0,0,TransferCodec.encodeWithPrefix(offer,0),List.of());}
}
