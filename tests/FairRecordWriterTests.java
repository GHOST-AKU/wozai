package dev.ghost.nearbyim.core;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
public final class FairRecordWriterTests {
    private static int checks;
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
    public static void main(String[] args)throws Exception {
        AtomicReference<Throwable> failure=new AtomicReference<>();CountDownLatch block=new CountDownLatch(1),running=new CountDownLatch(1),finished=new CountDownLatch(1);
        List<String> order=Collections.synchronizedList(new ArrayList<>());
        try(FairRecordWriter writer=new FairRecordWriter(failure::set)) {
            check(writer.pendingBytes()==0&&!Thread.getAllStackTraces().keySet().stream().anyMatch(t->t.getName().equals("nearby-writer")),"Idle writer creates a worker");
            check(writer.submit(()->{running.countDown();try{block.await();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}},32768,true),"Initial write rejected");
            check(running.await(2,TimeUnit.SECONDS),"Writer never starts");
            check(writer.pendingBytes()==32768+FairRecordWriter.ENTRY_OVERHEAD,"Active bytes missing from budget");
            for(int i=0;i<10;i++){final int n=i;check(writer.submit(()->order.add("file"+n),32768,true),"File queue rejected");}
            for(int i=0;i<9;i++){final int n=i;check(writer.submit(()->order.add("ack"+n),64,false),"Control queue rejected");}
            check(writer.submit(finished::countDown,64,true),"Finish rejected");block.countDown();check(finished.await(2,TimeUnit.SECONDS),"Writer stalled");
            check(order.subList(0,8).equals(Arrays.asList("ack0","ack1","ack2","ack3","ack4","ack5","ack6","ack7")),"Text/receipts are delayed by file queue");
            check(order.get(8).equals("file0")&&order.get(9).equals("ack8"),"Control flood starves the file");
            List<String> files=new ArrayList<>();for(String item:order)if(item.startsWith("file"))files.add(item);for(int i=0;i<10;i++)check(files.get(i).equals("file"+i),"File records reordered");
            check(failure.get()==null,"Writer unexpectedly failed");
        }
        CountDownLatch held=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger writes=new AtomicInteger();
        FairRecordWriter writer=new FairRecordWriter(failure::set);
        writer.submit(()->{held.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}},32768,true);check(held.await(2,TimeUnit.SECONDS),"Budget fixture stalled");
        int accepted=0;for(int i=0;i<512;i++){byte[] bytes=new byte[32768];if(writer.submit(()->{if(bytes[0]==0)writes.incrementAndGet();},bytes.length,true))accepted++;else break;}
        check(accepted>0&&accepted<512&&writer.pendingBytes()<=FairRecordWriter.FILE_BUDGET,"File byte budget exceeded");
        writer.close();release.countDown();check(!writer.submit(()->{},1,false),"Closed writer accepts work");
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while(writer.pendingBytes()!=0&&System.nanoTime()<deadline)Thread.yield();
        check(writer.pendingBytes()==0&&writes.get()==0,"Close retained queued file buffers");
        System.out.println("Fair record writer: "+checks+" ordering/budget/close checks passed");
    }
}
