package dev.ghost.wozai;

import dev.ghost.nearbyim.core.*;
import dev.ghost.nearbyim.i18n.UiText;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Durable outbox and actual production-to-production encrypted sessions. */
public final class ReadReceiptStoreTests {
    private static int checks;
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
    private static final String PEER=UUID.randomUUID().toString();
    public static void main(String[] args)throws Exception {
        Path root=Files.createTempDirectory("wozai-read-receipts-");
        try{persistence(root.resolve("store"));exchange(root.resolve("a"),root.resolve("b"));System.out.println("ReadReceiptStoreTests: "+checks+" checks passed");}
        finally{try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    }
    private static void persistence(Path root)throws Exception {
        String id=UUID.randomUUID().toString(),other=UUID.randomUUID().toString();
        try(DesktopStore store=new DesktopStore(root)){
            store.peer(new DesktopStore.Peer(PEER,"peer","",""));
            store.receive(PEER,id,"incoming",1);store.save(PEER,new DesktopStore.Message(id,"outgoing",2,true,"pending"));
            store.observed(other,List.of(id));check(store.pendingReads(PEER).isEmpty(),"Cross-peer read leaked");
            store.observed(PEER,List.of(id,id));check(store.pendingReads(PEER).equals(List.of(id)),"Visibility not deduplicated/persisted");
            store.receive(PEER,id,"incoming",1);check(store.pendingReads(PEER).equals(List.of(id)),"Duplicate TEXT lost pending read");
            store.read(PEER,List.of(id));store.acknowledge(PEER,id);store.sent(PEER,id);store.unknown(PEER);
            check(store.messages(PEER).stream().filter(DesktopStore.Message::outgoing).allMatch(m->m.status().equals("read")),"Late ACK/write/disconnect downgraded read");
        }
        try(DesktopStore store=new DesktopStore(root)){
            check(store.pendingReads(PEER).equals(List.of(id)),"Restart lost read outbox");
            check(store.messages(PEER).stream().filter(DesktopStore.Message::outgoing).allMatch(m->m.status().equals("read")),"Restart lost confirmed read");
            store.readAcknowledged(PEER,List.of(id));store.observed(PEER,List.of(id));check(store.pendingReads(PEER).isEmpty(),"ACKed read was queued again");
            String next=UUID.randomUUID().toString();store.receive(PEER,next,"not notified",1);store.observed(PEER,List.of(next));store.clearPendingReads();store.observed(PEER,List.of(next));check(store.pendingReads(PEER).isEmpty(),"Disable cleanup retroactively queued previous read");
        }
    }
    private static final class Events implements DesktopClient.Listener {
        volatile DesktopClient client;volatile DesktopClient.State latest;
        final BlockingQueue<DesktopClient.Request> requests=new LinkedBlockingQueue<>();
        public void changed(DesktopClient.State state){latest=state;}
        public void request(DesktopClient.Request request){requests.add(request);}
        public void notice(UiText text){}
    }
    @FunctionalInterface private interface Condition{boolean get()throws Exception;}
    private static void await(Condition condition,String description)throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(!condition.get()){if(System.nanoTime()>until)throw new AssertionError(description);Thread.sleep(10);}checks++;}
    private static void exchange(Path a,Path b)throws Exception {
        Events ea=new Events(),eb=new Events();
        try(DesktopStore sa=new DesktopStore(a);DesktopStore sb=new DesktopStore(b);
            DesktopClient ca=new DesktopClient(sa,DesktopIdentity.load(a.resolve("identity.properties")),ea);
            DesktopClient cb=new DesktopClient(sb,DesktopIdentity.load(b.resolve("identity.properties")),eb)){
            ea.client=ca;eb.client=cb;
            DesktopClient.Listening listening=cb.listen().get(3,TimeUnit.SECONDS);
            String endpoint=listening.endpoints().get(0);ca.connect(endpoint,null).get(3,TimeUnit.SECONDS);
            DesktopClient.Request request=eb.requests.poll(3,TimeUnit.SECONDS);check(request!=null,"Read peer consent missing");cb.approve(request,true).get(3,TimeUnit.SECONDS);
            await(()->ea.latest!=null&&ea.latest.phase().equals("ready")&&eb.latest!=null&&eb.latest.phase().equals("ready"),"Read peers not ready");
            String peerA=eb.latest.peer().id(),peerB=ea.latest.peer().id();
            await(()->ca.canObserveMessages(peerB)&&cb.canObserveMessages(peerA),"Reciprocal read policy not ready");
            check(ca.readReceiptsEnabled()&&cb.readReceiptsEnabled(),"Read receipts should default on");
            check(ca.send("actually visible").get(3,TimeUnit.SECONDS),"Read test text rejected");
            await(()->sa.messages(peerB).stream().anyMatch(m->m.outgoing()&&m.status().equals("delivered")),"Saved delivery missing");
            String id=sb.messages(peerA).get(0).id();check(sa.messages(peerB).get(0).status().equals("delivered"),"Delivery fabricated a read");
            cb.observedMessages(peerA,List.of(id)).get(3,TimeUnit.SECONDS);
            await(()->sa.messages(peerB).get(0).status().equals("read"),"Visible read not persisted across actual encrypted socket");
            await(()->sb.pendingReads(peerA).isEmpty(),"Durable read ACK did not retire outbox");
            cb.setReadReceiptsEnabled(false).get(3,TimeUnit.SECONDS);await(()->!ca.canObserveMessages(peerB),"Peer disable did not revoke reciprocal reads");
            check(ca.send("off means off").get(3,TimeUnit.SECONDS),"Privacy disabled text sending");
            await(()->sb.messages(peerA).size()==2,"Disabled peer did not save normal text");
            String second=sb.messages(peerA).stream().filter(m->!m.id().equals(id)).findFirst().orElseThrow().id();
            cb.observedMessages(peerA,List.of(second)).get(3,TimeUnit.SECONDS);check(sb.pendingReads(peerA).isEmpty(),"Disabled observation retained private read");
            cb.setReadReceiptsEnabled(true).get(3,TimeUnit.SECONDS);await(()->ca.canObserveMessages(peerB)&&cb.canObserveMessages(peerA),"Reenable did not renegotiate policy");
            check(sa.messages(peerB).stream().filter(m->m.id().equals(second)).noneMatch(m->m.status().equals("read")),"Reenable backfilled disabled observation");
            check(ca.send("read history save failure").get(3,TimeUnit.SECONDS),"Failure fixture text rejected");
            await(()->sb.messages(peerA).size()==3&&sa.messages(peerB).stream().filter(m->m.body().equals("read history save failure")).allMatch(m->m.status().equals("delivered")),"Failure fixture not delivered");
            String third=sb.messages(peerA).stream().filter(m->m.body().equals("read history save failure")).findFirst().orElseThrow().id();
            Path message=a.resolve("messages").resolve(peerB).resolve("out-"+third+".properties"),saved=a.resolve("held-receipt.properties");
            Files.move(message,saved);Files.createDirectory(message);
            try{
                cb.observedMessages(peerA,List.of(third)).get(3,TimeUnit.SECONDS);
                await(()->ea.latest.phase().equals("idle")&&eb.latest.phase().equals("idle"),"Read history failure did not close the affected session");
                check(sb.pendingReads(peerA).contains(third),"Failed read persistence was falsely acknowledged");
            }finally{Files.delete(message);Files.move(saved,message);}
            check(sa.messages(peerB).stream().filter(m->m.id().equals(third)).allMatch(m->m.status().equals("delivered")),"Failed read persistence fabricated a confirmed read");
            cb.observedMessages(peerA,List.of(second)).get(3,TimeUnit.SECONDS);
            check(sb.pendingReads(peerA).containsAll(List.of(second,third)),"Enabled offline observation or unconfirmed outbox lost");
            ca.connect(endpoint,peerB).get(3,TimeUnit.SECONDS);
            await(()->ea.latest.phase().equals("ready")&&eb.latest.phase().equals("ready"),"Pinned reconnect failed after receipt disk recovery");
            await(()->sa.messages(peerB).stream().filter(m->List.of(second,third).contains(m.id())).allMatch(m->m.status().equals("read")),"Reconnect did not retry exact durable read IDs");
            await(()->sb.pendingReads(peerA).isEmpty(),"Recovered read ACK did not retire durable offline outbox");

        }
    }
}
