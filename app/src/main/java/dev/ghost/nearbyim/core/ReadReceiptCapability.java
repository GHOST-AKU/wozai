package dev.ghost.nearbyim.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.function.*;

/** Connection-local negotiation and revocable, reciprocal read policies. */
public final class ReadReceiptCapability {
    public interface Sender { boolean send(ReadReceiptPacket packet,BooleanSupplier current); }
    private final LongConsumer tokenSender;
    private final Sender sender;
    private final Runnable changed;
    private final long request,response;
    private boolean started,replied,negotiated,enabled,peerEnabled;
    private long epoch=1,peerEpoch;
    private final Set<String> inFlight=new HashSet<>();
    private List<String> incoming=List.of();
    private boolean ackPending;

    public ReadReceiptCapability(String generation,LongConsumer tokens,Sender sender,Runnable changed) {
        if(generation==null||!generation.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Invalid connection generation");
        request=token(generation,"request",0x5251);response=token(generation,"response",0x5253);
        tokenSender=Objects.requireNonNull(tokens);this.sender=Objects.requireNonNull(sender);this.changed=Objects.requireNonNull(changed);
    }
    private static long token(String generation,String kind,int prefix) {
        try{byte[] digest=MessageDigest.getInstance("SHA-256").digest(("wozai-read-receipt-v1\0"+kind+"\0"+generation).getBytes(StandardCharsets.UTF_8));long value=prefix;for(int i=0;i<6;i++)value=(value<<8)|(digest[i]&255L);return value;}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    public synchronized void start(){if(!started){started=true;tokenSender.accept(request);}}
    public synchronized void receiveToken(long value) {
        if(value==request&&!replied){replied=true;tokenSender.accept(response);}
        if(value==response&&started&&!negotiated){negotiated=true;publishPolicy();changed.run();}
    }
    public synchronized void configure(boolean value) {
        if(enabled==value)return;
        enabled=value;epoch++;inFlight.clear();incoming=List.of();ackPending=false;
        if(negotiated)publishPolicy();changed.run();
    }
    private void publishPolicy(){long revision=epoch;sender.send(ReadReceiptPacket.policy(revision,enabled),()->policyCurrent(revision));}
    private synchronized boolean policyCurrent(long revision){return negotiated&&epoch==revision;}
    public synchronized boolean active(){return negotiated&&enabled&&peerEnabled;}
    public synchronized boolean current(ReadReceiptPacket packet){return active()&&packet.epoch==peerEpoch&&packet.peerEpoch==epoch;}
    /** Returns content packets only while both current policies allow them. */
    public synchronized boolean receive(ReadReceiptPacket packet)throws IOException {
        if(!replied)throw new IOException("Unnegotiated read receipt");
        if(packet.kind==ReadReceiptPacket.POLICY){
            if(packet.epoch<peerEpoch)return false;
            if(packet.epoch==peerEpoch){if(packet.enabled!=peerEnabled)throw new IOException("Conflicting read policy");return false;}
            peerEpoch=packet.epoch;peerEnabled=packet.enabled;inFlight.clear();incoming=List.of();ackPending=false;changed.run();return false;
        }
        if(!current(packet))return false;
        if(packet.kind==ReadReceiptPacket.ACK){if(ackPending||!inFlight.equals(new HashSet<>(packet.ids)))return false;ackPending=true;}
        else{if(!incoming.isEmpty()){if(incoming.equals(packet.ids))return false;throw new IOException("Read receipt window exceeded");}incoming=packet.ids;}
        return true;
    }
    public synchronized boolean send(List<String> ids) {
        if(!active()||!inFlight.isEmpty()||ids.isEmpty())return false;
        ReadReceiptPacket packet=ReadReceiptPacket.receipt(ReadReceiptPacket.READ,epoch,peerEpoch,ids);
        inFlight.addAll(ids);
        if(sender.send(packet,()->outgoingCurrent(packet)))return true;
        inFlight.clear();incoming=List.of();ackPending=false;return false;
    }
    private synchronized boolean outgoingCurrent(ReadReceiptPacket packet){return active()&&packet.epoch==epoch&&packet.peerEpoch==peerEpoch;}
    public synchronized boolean acknowledge(ReadReceiptPacket received) {
        if(received.kind!=ReadReceiptPacket.READ||!current(received))return false;
        ReadReceiptPacket ack=ReadReceiptPacket.receipt(ReadReceiptPacket.ACK,epoch,peerEpoch,received.ids);
        boolean sent=sender.send(ack,()->outgoingCurrent(ack));if(sent)incoming=List.of();return sent;
    }
    /** Release the batch only after its ACK was durably reflected in local history. */
    public synchronized void persisted(ReadReceiptPacket ack){if(ack.kind==ReadReceiptPacket.ACK&&current(ack)){inFlight.removeAll(ack.ids);ackPending=false;changed.run();}}
    public synchronized void close(){negotiated=false;enabled=false;peerEnabled=false;inFlight.clear();incoming=List.of();ackPending=false;}
}
