package dev.ghost.nearbyim.core;

import java.io.*;
import java.util.*;

/** Migration/probe records remain bounded and separate from user file packets. */
public final class FileLinkProtocolTests {
    public static void main(String[] args)throws Exception {
        Class<?> packet,codec,kind;
        try{packet=Class.forName("dev.ghost.nearbyim.core.FileLinkPacket");codec=Class.forName("dev.ghost.nearbyim.core.FileLinkCodec");kind=Class.forName(packet.getName()+"$Kind");}
        catch(ClassNotFoundException missing){throw new AssertionError("FILE has no separate bounded migration/probe envelope",missing);}
        String nonce=UUID.randomUUID().toString(),generation="a".repeat(64);
        byte[] data=new byte[32768];new Random(41).nextBytes(data);
        var make=packet.getMethod("data",String.class,String.class,int.class,long.class,byte[].class);
        var encode=codec.getMethod("encode",packet);var decode=codec.getMethod("decode",byte[].class);
        Object value=make.invoke(null,nonce,generation,0,0L,data);
        byte[] encoded=(byte[])encode.invoke(null,value);Object got=decode.invoke(null,(Object)encoded);
        check(Arrays.equals(data,(byte[])packet.getField("data").get(got)),"Probe data did not round trip");
        data[0]^=1;check(!Arrays.equals(data,(byte[])packet.getField("data").get(got)),"Probe payload aliases caller memory");
        check(encoded.length<34000,"A probe record exceeds one bounded file write");
        reject(()->decode.invoke(null,(Object)Arrays.copyOf(encoded,encoded.length+1)),"Trailing probe data accepted");
        reject(()->decode.invoke(null,(Object)Arrays.copyOf(encoded,encoded.length-1)),"Truncated probe data accepted");
        reject(()->make.invoke(null,nonce,generation,4,0L,new byte[1]),"Unbounded sample index accepted");
        reject(()->make.invoke(null,nonce,generation,0,4194304L,new byte[1]),"Probe offset exceeds per-round budget");
        reject(()->make.invoke(null,nonce,generation,0,0L,new byte[32769]),"Oversized probe record accepted");
        reject(()->make.invoke(null,nonce,"wrong",0,0L,new byte[1]),"Unbound generation accepted");
        var wrapping=ProtocolV4.class.getMethod("encodeFileLink",packet);
        byte[] plain=(byte[])wrapping.invoke(null,value);
        Object record=ProtocolV4.decodeRecord(plain);
        check(record.getClass().getField("fileLink").get(record)!=null,"NIM4 lost its separate migration envelope");
        check(((ProtocolV4.Record)record).transfer==null&&((ProtocolV4.Record)record).frame==null,"Probe entered the attachment or text path");
        System.out.println("FILE link protocol: bounded data, immutable payload, budget/index/generation, malformed envelope and NIM4 isolation passed");
    }
    private interface Work{void run()throws Exception;}
    private static void reject(Work work,String message)throws Exception{try{work.run();}catch(java.lang.reflect.InvocationTargetException error){if(error.getCause() instanceof IOException||error.getCause() instanceof IllegalArgumentException)return;throw error;}throw new AssertionError(message);}
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
