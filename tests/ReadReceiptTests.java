package dev.ghost.nearbyim.core;

import java.io.*;
import java.util.*;

/** Wire compatibility and privacy boundaries, without wall-clock assumptions. */
public final class ReadReceiptTests {
    public static void main(String[] args) throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
        out.writeInt(0x4e494d34);out.writeByte(4);out.writeByte(20);out.writeLong(0);out.writeInt(0);out.writeInt(0);
        byte[] policy={1,0,0,0,0,0,0,0,0,1,0,0,0,0,0,0,0,0,1};
        out.writeInt(policy.length);out.write(policy);
        Frame frame;
        try{frame=ProtocolV4.decode(bytes.toByteArray());}catch(IOException missing){throw new AssertionError("NIM4 must decode a bounded read-receipt policy record",missing);}
        if(frame.type!=20||!Arrays.equals(frame.data,policy))throw new AssertionError("Read receipt binary round trip");
        malformed();policies();
        System.out.println("ReadReceiptTests: wire, reciprocal policies and queued-revocation passed");
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static void malformed()throws Exception {
        for(byte[] invalid:new byte[][]{new byte[0],new byte[18],new byte[ReadReceiptPacket.MAX_BYTES+1]}){
            try{ReadReceiptPacket.decode(invalid);throw new AssertionError("Unbounded read packet accepted");}catch(IOException expected){}
        }
        String id=UUID.randomUUID().toString();
        ReadReceiptPacket p=ReadReceiptPacket.receipt(ReadReceiptPacket.READ,1,2,List.of(id));
        check(ReadReceiptPacket.decode(p.encode()).ids.equals(List.of(id)),"Read IDs changed");
        byte[] trailing=Arrays.copyOf(p.encode(),p.encode().length+1);
        try{ReadReceiptPacket.decode(trailing);throw new AssertionError("Trailing data accepted");}catch(IOException expected){}
        try{ReadReceiptPacket.receipt(ReadReceiptPacket.READ,1,2,List.of(id,id));throw new AssertionError("Duplicate IDs accepted");}catch(IllegalArgumentException expected){}
    }
    private static void policies()throws Exception {
        final String generation="a".repeat(64),id=UUID.randomUUID().toString();
        ArrayDeque<Runnable> queue=new ArrayDeque<>();List<ReadReceiptPacket> delivered=new ArrayList<>();
        ReadReceiptCapability[] ends=new ReadReceiptCapability[2];
        for(int i=0;i<2;i++){final int n=i;ends[i]=new ReadReceiptCapability(generation,t->queue.add(()->ends[1-n].receiveToken(t)),(p,current)->{queue.add(()->{if(current.getAsBoolean())try{if(ends[1-n].receive(p))delivered.add(p);}catch(IOException error){throw new AssertionError(error);}});return true;},()->{});}
        ends[0].configure(true);ends[0].start();ends[1].start();drain(queue);
        check(!ends[0].active()&&!ends[1].active(),"One-sided opt-in enabled reads");
        ends[1].configure(true);drain(queue);check(ends[0].active()&&ends[1].active(),"Mutual opt-in not negotiated");
        check(ends[0].send(List.of(id)),"Read send rejected");ends[0].configure(false);drain(queue);
        check(delivered.isEmpty()&&!ends[1].active(),"Disabled queued read leaked");
        ends[0].configure(true);drain(queue);check(ends[0].send(List.of(id)),"Re-enabled read rejected");drain(queue);
        ReadReceiptPacket read=delivered.remove(0);check(ends[1].acknowledge(read),"Durable read ACK rejected");drain(queue);
        ReadReceiptPacket ack=delivered.remove(0);check(ack.kind==ReadReceiptPacket.ACK,"Read ACK lost");
        check(!ends[0].send(List.of(id)),"Batch not bounded while ACK unpersisted");ends[0].persisted(ack);
        check(ends[0].send(List.of(id)),"Persisted ACK did not release outbox batch");
        ends[1].configure(false);ends[1].configure(true);drain(queue);
        check(!ends[1].current(read),"Previous privacy epoch accepted");
        ends[0].close();check(!ends[0].active(),"Closed connection retains read capability");
        ReadReceiptCapability unsupported=new ReadReceiptCapability("b".repeat(64),t->{},(p,c)->{throw new AssertionError("Unknown record sent to legacy peer");},()->{});
        unsupported.configure(true);unsupported.start();unsupported.receiveToken(42);check(!unsupported.active(),"Legacy PONG activated reads");
        try{unsupported.receive(ReadReceiptPacket.policy(1,true));throw new AssertionError("Unsolicited extension accepted");}catch(IOException expected){}
    }
    private static void drain(ArrayDeque<Runnable> queue){int budget=100;while(!queue.isEmpty()){if(--budget==0)throw new AssertionError("Read policy echo loop");queue.remove().run();}}
}
