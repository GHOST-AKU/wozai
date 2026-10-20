package dev.ghost.nearbyim.core;

import java.io.*;
import java.util.*;

public final class AttachmentV2ControlTests {
    private static int checks;
    private static final String ID="11111111-1111-1111-1111-111111111111",GEN="22222222-2222-2222-2222-222222222222",CONNECTION="a".repeat(64);
    public static void main(String[] args) throws Exception {
        AttachmentInfo info=AttachmentInfo.v2(ID,"large.bin","application/octet-stream",TransferLimits.MAX_FILE_BYTES,null,1);
        AttachmentRecord pending=new AttachmentRecord(info,true,"offered",0);
        check(AttachmentRecord.decode(pending.encode()).equals(pending),"Unknown final digest not preserved");
        rejects(info::offer);
        rejects(()->new AttachmentRecord(info,false,"received",info.size));
        TransferPacket offer=TransferPacket.offer(info,GEN,CONNECTION);
        TransferPacket decoded=TransferCodec.read(new ByteArrayInputStream(encode(offer)));
        check(decoded.info.size==info.size&&decoded.info.hash==null,"v2 offer changed");
        byte[] original=new byte[]{1,2,3};
        TransferPacket data=TransferPacket.data(ID,GEN,CONNECTION,info.size,(1L<<32)+1,original);original[0]=99;
        decoded=TransferCodec.read(new ByteArrayInputStream(encode(data)));
        check(decoded.offset==(1L<<32)+1&&decoded.data[0]==1,"Offset narrowed or payload not owned");
        rejects(()->TransferPacket.data(ID,GEN,CONNECTION,10,Long.MAX_VALUE,new byte[1]));
        rejects(()->TransferPacket.data(ID,GEN,CONNECTION,100_000,0,new byte[TransferLimits.DATA_BYTES+1]));
        byte[] bad=encode(data);bad[8]=127;rejects(()->TransferCodec.read(new ByteArrayInputStream(bad)));
        byte[] huge={127,-1,-1,-1};rejects(()->TransferCodec.read(new ByteArrayInputStream(huge)));
        byte[] truncated=Arrays.copyOf(encode(data),12);rejects(()->TransferCodec.read(new ByteArrayInputStream(truncated)));
        TransferByteWindow window=new TransferByteWindow(65536,1048576);
        check(window.tryReserve(0,32768)&&window.tryReserve(32768,32768),"Initial window incorrect");
        check(!window.tryReserve(65536,1)&&window.inFlightBytes()==65536,"Window overspent");
        window.acknowledge(32768,65536);check(window.tryReserve(65536,32768),"Credit did not release space");
        rejects(()->window.acknowledge(1,65536));rejects(()->window.acknowledge(1_000_000,65536));rejects(()->window.acknowledge(32768,1048577));
        try(TransferBufferPool pool=new TransferBufferPool(32768,32768)) {
            TransferBufferPool.Lease first=pool.acquire();check(pool.retainedBytes()==32768,"Pool budget incorrect");
            rejects(pool::acquire);first.bytes()[0]=42;first.close();first.close();
            try(TransferBufferPool.Lease reused=pool.acquire()){check(reused.bytes()[0]==0&&pool.retainedBytes()==32768,"Buffer not cleared/reused");}
        }
        System.out.println("AttachmentV2ControlTests: "+checks+" checks passed");
    }
    private static byte[] encode(TransferPacket packet)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();TransferCodec.write(out,packet);return out.toByteArray();}
    private interface Action{void run()throws Exception;}
    private static void rejects(Action action)throws Exception{try{action.run();}catch(IOException expected){checks++;return;}throw new AssertionError("Expected IOException");}
    private static void check(boolean valid,String reason){if(!valid)throw new AssertionError(reason);checks++;}
}
