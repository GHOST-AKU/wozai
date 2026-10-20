package dev.ghost.nearbyim.core;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
/** Probe fences share FILE FIFO even when the control burst has reached its fairness limit. */
public final class FileLinkWriterOrderingTests {
    public static void main(String[] args)throws Exception{
        try(var pair=new AutomaticFileRoutesTests.Pair("lan",false,false)){
            pair.a.wire.enableFileLink();pair.b.wire.enableFileLink();List<FileLinkPacket.Kind> received=Collections.synchronizedList(new ArrayList<>());CountDownLatch packets=new CountDownLatch(3);
            pair.b.linkConsumer=packet->{received.add(packet.kind);packets.countDown();};
            var field=FramedSession.class.getDeclaredField("writer");field.setAccessible(true);var writer=(FairRecordWriter)field.get(pair.a.wire);
            CountDownLatch held=new CountDownLatch(1),release=new CountDownLatch(1);
            for(int i=0;i<7;i++)if(!writer.submit(()->{},1,false))throw new AssertionError("Control setup rejected");
            writer.submit(()->{held.countDown();try{release.await(3,TimeUnit.SECONDS);}catch(InterruptedException error){Thread.currentThread().interrupt();throw new IOException(error);}},1,false);
            if(!held.await(3,TimeUnit.SECONDS))throw new AssertionError("Fairness barrier did not start");
            try{
                String nonce=UUID.randomUUID().toString(),generation=pair.a.wire.connectionGeneration();
                if(!pair.a.wire.sendFileLink(FileLinkPacket.probe(FileLinkPacket.Kind.PROBE_START,nonce,generation,0,0))||!pair.a.wire.sendFileLink(FileLinkPacket.data(nonce,generation,0,0,new byte[]{1}))||!pair.a.wire.sendFileLink(FileLinkPacket.probe(FileLinkPacket.Kind.PROBE_END,nonce,generation,0,0)))throw new AssertionError("Probe queue rejected");
            }finally{release.countDown();}
            if(!packets.await(3,TimeUnit.SECONDS))throw new AssertionError("Probe packets did not arrive");
            if(!received.equals(List.of(FileLinkPacket.Kind.PROBE_START,FileLinkPacket.Kind.PROBE_DATA,FileLinkPacket.Kind.PROBE_END)))throw new AssertionError("Fair writer reordered probe fences: "+received);
            System.out.println("FILE probe writer: START/DATA/END stay ordered after a full control burst");
        }
    }
}
