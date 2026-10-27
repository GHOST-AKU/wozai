package dev.ghost.nearbyim.core;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.lang.reflect.*;
import java.util.*;
public final class TextInputTests {
    public static void main(String[] args)throws Exception{
        for(String value:List.of("a".repeat(2000),"a".repeat(2001),"中".repeat(2001),"👩🏽‍💻e\u0301".repeat(1000),"a".repeat(32768))){
            check(TextLimits.utf8Bytes(value)==value.getBytes(StandardCharsets.UTF_8).length,"UTF-8 count differs");
            check(TextLimits.canSend(value,32768),"valid long input rejected");
        }
        check(TextLimits.canSend("a".repeat(8192),8192)&&!TextLimits.canSend("a".repeat(8193),8192),"legacy boundary");
        check(!TextLimits.canSend("a".repeat(32769),32768)&&!TextLimits.canSend("\ud800",32768),"oversize/malformed boundary");
        List<Long> sent=new ArrayList<>();int[] changes={0};String generation="a".repeat(64);
        TextCapability cap=new TextCapability(generation,sent::add,()->changes[0]++);
        check(TextCapability.token(generation,"request",0x574f)==0x574f57812e4e8cd3L,"request vector");
        check(TextCapability.token(generation,"response",0x5752)==0x575213fb17e249aeL,"response vector");
        cap.receive(sent.isEmpty()?0:sent.get(0));cap.start();cap.start();
        cap.receive(TextCapability.token("b".repeat(64),"response",0x5752));check(cap.outgoing()==8192&&sent.size()==1,"stale response/repeated request");
        cap.receive(TextCapability.token(generation,"request",0x574f));cap.receive(TextCapability.token(generation,"request",0x574f));
        check(sent.size()==2&&cap.incoming()==32768&&cap.outgoing()==8192,"directional negotiation");
        cap.receive(TextCapability.token(generation,"response",0x5752));cap.receive(TextCapability.token(generation,"response",0x5752));
        check(changes[0]==1&&cap.outgoing()==32768,"echo must upgrade exactly once");
        Frame extended=new Frame(Frame.TEXT,UUID.randomUUID().toString(),"x".repeat(8193),1);
        byte[] record=ProtocolV4.encode(extended,32768);
        try{ProtocolV4.decode(record);throw new AssertionError("Unnegotiated text accepted");}catch(java.io.IOException expected){}
        check(ProtocolV4.decode(record,32768).body.equals(extended.body),"negotiated codec");
        Class<?> store;
        try{store=Class.forName("dev.ghost.nearbyim.core.DraftStore");}catch(ClassNotFoundException missing){throw new AssertionError("Long drafts need bounded durable storage",missing);}
        Path root=Files.createTempDirectory("long-draft");Object drafts=store.getConstructor(Path.class).newInstance(root);
        Method save=store.getMethod("save",String.class,String.class),load=store.getMethod("load",String.class);
        String id=UUID.randomUUID().toString(),text="中".repeat(60000)+"👩🏽‍💻e\u0301\ud800";
        save.invoke(drafts,id,text);check(text.equals(load.invoke(store.getConstructor(Path.class).newInstance(root),id)),"draft changed after restart");
        try{save.invoke(drafts,id,"a".repeat(TextLimits.DRAFT_BYTES+1));throw new AssertionError("unbounded draft saved");}catch(InvocationTargetException expected){check(expected.getCause() instanceof java.io.IOException,"wrong failure");}
        check(text.equals(load.invoke(drafts,id)),"oversized draft destroyed previous draft");
        save.invoke(drafts,id,"");check("".equals(load.invoke(drafts,id)),"empty draft is not authoritative");
        try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}
        System.out.println("Text input: UTF-8 boundaries, Unicode, durable long drafts, atomic oversize rejection passed");
    }
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
