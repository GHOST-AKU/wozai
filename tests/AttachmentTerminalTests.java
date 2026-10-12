package dev.ghost.nearbyim.core;

import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Completion means the durable task no longer occupies its session's slot. */
public final class AttachmentTerminalTests {
    public static void main(String[] args) throws Exception {
        Path root=Files.createTempDirectory("attachment-terminal-");
        AttachmentTransferV2[] peers=new AttachmentTransferV2[2];
        BlockingQueue<Boolean> sender=new LinkedBlockingQueue<>(),receiver=new LinkedBlockingQueue<>();
        String a="a".repeat(64),b="b".repeat(64),connection="c".repeat(64);
        try {
            peers[0]=new AttachmentTransferV2(root.resolve("a"),a,b,connection,false,new AttachmentTransferV2.Wire(){
                public boolean send(TransferPacket packet){try{peers[1].receive(packet,true);return true;}catch(Exception e){return false;}}
                public void abort(){}
            },record->{if(record.state.equals("delivered"))sender.add(!peers[0].hasActive());});
            peers[1]=new AttachmentTransferV2(root.resolve("b"),b,a,connection,false,new AttachmentTransferV2.Wire(){
                public boolean send(TransferPacket packet){try{peers[0].receive(packet,true);return true;}catch(Exception e){return false;}}
                public void abort(){}
            },record->{if(record.state.equals("received"))receiver.add(!peers[1].hasActive());});
            Path original=root.resolve("original.bin");Files.write(original,new byte[131075]);
            peers[0].offer(new FileAttachmentSource(original),"original.bin","application/octet-stream").get(3,TimeUnit.SECONDS);
            Boolean released=sender.poll(5,TimeUnit.SECONDS);
            if(!Boolean.TRUE.equals(released))throw new AssertionError("Delivered event still occupies the sender slot: "+released);
            if(!Boolean.TRUE.equals(receiver.poll(3,TimeUnit.SECONDS)))throw new AssertionError("Received event still occupies the receiver slot");
            if(!Files.exists(original))throw new AssertionError("Delivery removed user original");
            System.out.println("AttachmentTerminalTests: durable sender and receiver slots released before final history event");
        } finally {
            for(AttachmentTransferV2 peer:peers)if(peer!=null)peer.shutdown().get(5,TimeUnit.SECONDS);
            try(var paths=Files.walk(root)){for(Path path:paths.sorted(java.util.Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}
        }
    }
}
