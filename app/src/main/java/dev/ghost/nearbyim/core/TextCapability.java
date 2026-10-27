package dev.ghost.nearbyim.core;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.function.*;

/** Old NIM4 peers ignore PONG timestamps. Tokens are scoped to the authenticated generation. */
public final class TextCapability {
    private final long request,response;
    private final LongConsumer send;
    private final Runnable changed;
    private boolean started,replied;
    private volatile int incoming=TextLimits.LEGACY_BYTES,outgoing=TextLimits.LEGACY_BYTES;
    public TextCapability(String generation,LongConsumer send,Runnable changed){
        if(generation==null||!generation.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Invalid connection generation");
        request=token(generation,"request",0x574f);response=token(generation,"response",0x5752);this.send=send;this.changed=changed;
    }
    public synchronized void start(){if(!started){started=true;send.accept(request);}}
    public synchronized void receive(long timestamp){
        if(timestamp==request&&!replied){
            replied=true;incoming=TextLimits.EXTENDED_BYTES;
            // The ordered writer queues the echo before the peer can send enlarged TEXT.
            send.accept(response);
        }else if(timestamp==response&&started&&outgoing!=TextLimits.EXTENDED_BYTES){outgoing=TextLimits.EXTENDED_BYTES;changed.run();}
    }
    public int incoming(){return incoming;} public int outgoing(){return outgoing;}
    static long token(String generation,String kind,int prefix){
        try{
            byte[] digest=MessageDigest.getInstance("SHA-256").digest(("wozai-text-capability-v1\0"+kind+"\0"+generation).getBytes(StandardCharsets.UTF_8));
            long value=prefix;for(int i=0;i<6;i++)value=(value<<8)|(digest[i]&255L);return value;
        }catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
}
