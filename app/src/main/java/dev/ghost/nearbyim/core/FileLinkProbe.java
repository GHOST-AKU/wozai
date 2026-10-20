package dev.ghost.nearbyim.core;

import java.io.IOException;
import java.util.concurrent.*;
import java.util.function.*;

/** Four alternating encrypted samples. Only the routing actor mutates counters. */
final class FileLinkProbe implements AutoCloseable {
    interface Host {
        boolean send(boolean lan,FileLinkPacket packet,BooleanSupplier live);
        ScheduledExecutorService timer();void done(FileRouteQualityPolicy.Sample[] samples);
        void failed(IOException error);
    }
    private final Host host;private final String nonce,generation;private final boolean sender;
    private final FileRouteQualityPolicy.Sample[] samples=new FileRouteQualityPolicy.Sample[4];
    private volatile int round;private long started,queued,acknowledged,received;
    private boolean receiving,ending;private volatile boolean closed;private volatile boolean writing;
    private ScheduledFuture<?> timeout,roundEnd;
    FileLinkProbe(Host host,String nonce,String generation,boolean sender){this.host=host;this.nonce=nonce;this.generation=generation;this.sender=sender;}
    void start(){timeout=host.timer().schedule(()->finish(),2,TimeUnit.SECONDS);if(sender)next();}
    private void next(){
        if(closed)return;if(round==4){finish();return;}
        queued=acknowledged=0;started=System.nanoTime();ending=false;writing=true;
        send(FileLinkPacket.probe(FileLinkPacket.Kind.PROBE_START,nonce,generation,round,0),()->true);
        // Leave time for the final ACK; incomplete measurements remain conservative.
        roundEnd=host.timer().schedule(this::endRound,400,TimeUnit.MILLISECONDS);pump();
    }
    private void pump(){
        while(!closed&&!ending&&queued<FileRouteQualityPolicy.ROUND_BYTES&&queued-acknowledged<131072){
            int count=(int)Math.min(32768,FileRouteQualityPolicy.ROUND_BYTES-queued);int sample=round;
            if(!send(FileLinkPacket.data(nonce,generation,round,queued,new byte[count]),()->writing&&!closed&&round==sample))return;
            queued+=count;
        }
        if(queued==FileRouteQualityPolicy.ROUND_BYTES&&acknowledged==queued)endRound();
    }
    private void endRound(){
        if(closed||ending)return;ending=true;writing=false;if(roundEnd!=null)roundEnd.cancel(false);
        send(FileLinkPacket.probe(FileLinkPacket.Kind.PROBE_END,nonce,generation,round,0),()->!closed);
    }
    void receive(FileLinkPacket packet,boolean lan){
        if(closed)return;
        if(packet.number!=round||lan!=(round%2==1)){bad("sample "+packet.kind+" received="+packet.number+" expected="+round+" lan="+lan);return;}
        if(sender){
            if(packet.kind==FileLinkPacket.Kind.PROBE_ACK){if(packet.value<acknowledged||packet.value>queued){bad();return;}acknowledged=packet.value;pump();}
            else if(packet.kind==FileLinkPacket.Kind.PROBE_DONE&&ending&&packet.value<=queued){
                long elapsed=System.nanoTime()-started;samples[round]=new FileRouteQualityPolicy.Sample(packet.value,elapsed,elapsed<=FileRouteQualityPolicy.ROUND_NS);
                round++;next();
            }else bad();
        }else{
            switch(packet.kind){
                case PROBE_START:if(receiving||packet.value!=0){bad();return;}receiving=true;received=0;break;
                case PROBE_DATA:
                    if(!receiving||packet.value!=received){bad("offset "+packet.value+" expected="+received+" active="+receiving);return;}received+=packet.data.length;
                    send(FileLinkPacket.probe(FileLinkPacket.Kind.PROBE_ACK,nonce,generation,round,received),()->!closed);break;
                case PROBE_END:
                    if(!receiving){bad();return;}receiving=false;
                    send(FileLinkPacket.probe(FileLinkPacket.Kind.PROBE_DONE,nonce,generation,round,received),()->true);
                    if(++round==4)finish();break;
                default:bad();
            }
        }
    }
    private boolean send(FileLinkPacket packet,BooleanSupplier current){if(host.send(round%2==1,packet,current))return true;bad();return false;}
    private void bad(){bad("unexpected phase");}
    private void bad(String reason){close();host.failed(new IOException("Invalid bounded FILE probe: "+reason));}
    private void finish(){if(closed)return;close();host.done(samples);}
    public void close(){closed=true;writing=false;if(timeout!=null)timeout.cancel(false);if(roundEnd!=null)roundEnd.cancel(false);}
}
