package dev.ghost.nearbyim.core;
import java.io.*;
import java.util.*;
public final class ProtocolV4Tests {
    private static int checks;
    private interface Action{void run()throws Exception;}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
    private static void rejects(Action action)throws Exception{try{action.run();}catch(IOException expected){checks++;return;}throw new AssertionError("Invalid NIM4 frame accepted");}
    public static void main(String[] args)throws Exception {
        for(int type:new int[]{Frame.READY,Frame.TEXT,Frame.ACK,Frame.BYE,Frame.PING,Frame.PONG}) {
            Frame frame=new Frame(type,type==Frame.TEXT||type==Frame.ACK?UUID.randomUUID().toString():"",type==Frame.TEXT?"消息🙂":"",7);
            Frame decoded=ProtocolV4.decode(ProtocolV4.encode(frame));check(frame.type==decoded.type&&frame.id.equals(decoded.id)&&frame.body.equals(decoded.body)&&frame.timestamp==decoded.timestamp,"Text/control round trip changed");
        }
        String id=UUID.randomUUID().toString(),generation=UUID.randomUUID().toString(),connection=UUID.randomUUID().toString();byte[] data=new byte[TransferLimits.DATA_BYTES];new Random(32).nextBytes(data);
        TransferPacket packet=TransferPacket.data(id,generation,connection,TransferLimits.MAX_FILE_BYTES,4L*1024*1024*1024,data);
        ByteArrayOutputStream wire=new ByteArrayOutputStream();TransferCodec.write(wire,packet);
        Frame frame=new Frame(Frame.ATTACHMENT_V2,"","",0,0,wire.toByteArray());byte[] encoded=ProtocolV4.encode(frame);
        Frame decoded=ProtocolV4.decode(encoded);TransferPacket result=TransferCodec.read(new ByteArrayInputStream(decoded.data));
        check(result.offset==4L*1024*1024*1024&&result.totalSize==TransferLimits.MAX_FILE_BYTES&&Arrays.equals(result.data,data),"V2 lost long offsets or original bytes");
        byte[] trailing=Arrays.copyOf(encoded,encoded.length+1);rejects(()->ProtocolV4.decode(trailing));
        byte[] wrongVersion=encoded.clone();wrongVersion[4]=3;rejects(()->ProtocolV4.decode(wrongVersion));
        rejects(()->ProtocolV4.decode(new byte[ProtocolV4.MAX_PLAINTEXT_BYTES+1]));
        rejects(()->ProtocolV4.encode(new Frame(Frame.HELLO,id,"name",0)));
        rejects(()->ProtocolV4.encode(new Frame(Frame.FILE_CHUNK,id,"",0,0,new byte[]{1})));
        rejects(()->ProtocolV4.encode(new Frame(Frame.ATTACHMENT_V2,id,"",0,0,wire.toByteArray())));
        rejects(()->ProtocolV4.encode(new Frame(Frame.ATTACHMENT_V2,"","",0,0,new byte[]{1,2,3})));
        rejects(()->ProtocolV4.encode(new Frame(Frame.TEXT,id,"\ud800",0)));
        System.out.println("NIM4 codec: "+checks+" checks passed");
    }
}
