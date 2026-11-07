package org.nearbymesh.radio;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Production stream tests; pipe/TCP substitutes are not Bluetooth hardware evidence. */
public final class CocStreamsTest {
    static void check(boolean v,String why){if(!v)throw new AssertionError(why);}
    static void waitFor(java.util.function.BooleanSupplier v)throws Exception{
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(!v.getAsBoolean()&&System.nanoTime()<end)Thread.sleep(5);
        check(v.getAsBoolean(),"condition timeout");
    }
    static final class Clock implements CocRuntime.Time{
        long now; final List<Timer> timers=new ArrayList<>();
        static final class Timer{long due;Runnable r;boolean canceled;}
        public synchronized long now(){return now;}
        public synchronized CocRuntime.Ticket after(long delay,Runnable r){Timer t=new Timer();t.due=now+Math.max(0,delay);t.r=r;timers.add(t);return()->{synchronized(this){t.canceled=true;}};}
        void advance(long delta){long end; synchronized(this){end=now+delta;}
            while(true){Timer next=null;synchronized(this){for(Timer t:timers)if(!t.canceled&&t.due<=end&&(next==null||t.due<next.due))next=t;
                if(next==null){now=end;return;}now=next.due;next.canceled=true;}next.r.run();}
        }
    }
    static final class Observer implements CocStreams.Observer{
        final List<String> events=new CopyOnWriteArrayList<>();
        public void event(String event,String peer,String reason,long tx,long rx){events.add(event+":"+reason);}
        public void capacityChanged(){}
        public void progress(CocStreams.Link link){}
    }
    static final class Pipe implements CocStreams.Socket{
        final InputStream in;final OutputStream out;final AtomicInteger closes=new AtomicInteger();
        Pipe(InputStream in,OutputStream out){this.in=in;this.out=out;}
        public void connect(){}
        public InputStream input(){return in;}
        public OutputStream output(){return out;}
        public void close(){if(closes.incrementAndGet()!=1)throw new AssertionError("socket closed twice");try{in.close();}catch(IOException ignored){}try{out.close();}catch(IOException ignored){}}
    }
    static final class Core implements AutoCloseable{
        final ServerSocket server=new ServerSocket(0,1,InetAddress.getLoopbackAddress());volatile Socket socket;
        final CountDownLatch connected=new CountDownLatch(1);
        Core()throws Exception{Thread t=new Thread(()->{try{socket=server.accept();socket.setSoTimeout(5000);connected.countDown();}catch(IOException ignored){}});t.setDaemon(true);t.start();}
        int port(){return server.getLocalPort();}
        Socket get()throws Exception{check(connected.await(5,TimeUnit.SECONDS),"CoreBridge must connect");return socket;}
        public void close()throws IOException{server.close();if(socket!=null)socket.close();}
    }
    public static void main(String[] args)throws Exception{
        protocol(); capacityAndLateFactory(); blockedConnectAndWrite(); coreFailureNeverReportsUp(); duplex();
        System.out.println("CocStreamsTest: 5 groups passed (protocol, ownership/deadlines, capacity, duplex CoreBridge)");
    }
    static void protocol()throws Exception{
        byte[] adv=CocProtocol.advertisement("12345678",0x1234);
        check(adv.length==12&&adv[10]==0x12&&adv[11]==0x34,"independent byte-order/budget vector");
        RadioConfig c=RadioConfig.publicMesh("ble","A",39770);
        check(CocProtocol.parse(adv,c).psm==0x1234,"offer roundtrip");
        for(byte[] bad:new byte[][]{new byte[0],new byte[]{1,1,'B',0,0},new byte[]{2,1,'B',0,1},new byte[]{1,1,(byte)255,0,1},new byte[]{1,2,'B',0,1}})
            check(CocProtocol.parse(bad,c)==null,"invalid offer rejected");
        byte[] wire=new byte[]{'N','M','L','C',1,1,'B',0x52,11,12};
        InputStream fragmented=new FilterInputStream(new ByteArrayInputStream(wire)){
            public int read(byte[] b,int o,int n)throws IOException{return super.read(b,o,Math.min(n,1));}};
        check("B".equals(CocProtocol.readPreface(fragmented)),"fragmented preface");
        check(fragmented.read()==0x52&&fragmented.read()==11&&fragmented.read()==12,"no prefetched Core bytes lost");
        try{CocProtocol.readPreface(new ByteArrayInputStream(new byte[]{'N','M','L','C',1,9}));throw new AssertionError("length accepted");}catch(IOException expected){}
    }
    static void capacityAndLateFactory()throws Exception{
        Clock clock=new Clock();List<Runnable> queued=new ArrayList<>();AtomicInteger opens=new AtomicInteger();Observer observer=new Observer();
        CocStreams streams=new CocStreams(RadioConfig.publicMesh("ble","A",39770),observer,queued::add,clock);
        for(String peer:new String[]{"B","C","D","E"})check(streams.dial(peer,()->{opens.incrementAndGet();return new Pipe(new ByteArrayInputStream(new byte[0]),new ByteArrayOutputStream());}),"four slots");
        check(!streams.dial("F",()->null)&&streams.size()==4,"pending count in capacity");
        clock.advance(15000);check(streams.size()==0,"absolute setup deadline frees all slots");
        for(Runnable r:queued)r.run();check(opens.get()==0,"expired queued work cannot create sockets");streams.close();
        CountDownLatch factoryEntered=new CountDownLatch(1),release=new CountDownLatch(1);
        Pipe late=new Pipe(new ByteArrayInputStream(new byte[0]),new ByteArrayOutputStream());
        ExecutorService worker=Executors.newSingleThreadExecutor();
        CocStreams other=new CocStreams(RadioConfig.publicMesh("ble","A",39770),observer,worker,clock);
        try{other.dial("B",()->{factoryEntered.countDown();try{release.await();}catch(InterruptedException e){throw new IOException(e);}return late;});
            check(factoryEntered.await(2,TimeUnit.SECONDS),"factory entered");other.close();release.countDown();waitFor(()->late.closes.get()==1);check(other.size()==0,"late creation not revived");
        }finally{release.countDown();other.close();worker.shutdownNow();}
    }
    static void blockedConnectAndWrite()throws Exception{
        for(boolean connect:new boolean[]{true,false}){
            Clock clock=new Clock();Observer obs=new Observer();CountDownLatch entered=new CountDownLatch(1),unblock=new CountDownLatch(1);AtomicInteger closes=new AtomicInteger();
            CocStreams.Socket socket=new CocStreams.Socket(){
                void block()throws IOException{entered.countDown();try{unblock.await();}catch(InterruptedException e){throw new IOException(e);}throw new IOException("closed");}
                public void connect()throws IOException{if(connect)block();}
                public InputStream input(){return new ByteArrayInputStream(new byte[0]);}
                public OutputStream output(){return new OutputStream(){public void write(int b)throws IOException{block();}};}
                public void close(){check(closes.incrementAndGet()==1,"close once");unblock.countDown();}
            };
            ExecutorService worker=Executors.newSingleThreadExecutor();CocStreams streams=new CocStreams(RadioConfig.publicMesh("ble","A",39770),obs,worker,clock);
            try{streams.dial("B",()->socket);check(entered.await(2,TimeUnit.SECONDS),"blocking IO entered");clock.advance(connect?15000:5000);waitFor(()->streams.size()==0);check(closes.get()==1,"deadline closes blocked operation");
            }finally{streams.close();unblock.countDown();worker.shutdownNow();}
        }
    }
    static void coreFailureNeverReportsUp()throws Exception{
        int port;try(ServerSocket unused=new ServerSocket(0)){port=unused.getLocalPort();}
        Observer observer=new Observer();Clock clock=new Clock();
        CocStreams streams=new CocStreams(RadioConfig.publicMesh("ble","A",port),observer,Runnable::run,clock);
        Pipe socket=new Pipe(new ByteArrayInputStream(new byte[]{'N','M','L','C',1,1,'B',0x52}),new ByteArrayOutputStream());
        streams.dial("B",()->socket);
        check(streams.size()==0&&socket.closes.get()==1,"failed Core releases its socket");
        check(observer.events.stream().noneMatch(e->e.startsWith("ble_link_up:")),"failed Core cannot publish link up");
        streams.close();
    }
    static void duplex()throws Exception{
        try(Core a=new Core();Core b=new Core()){
            PipedInputStream ai=new PipedInputStream(65536),bi=new PipedInputStream(65536);
            Pipe ap=new Pipe(ai,new PipedOutputStream(bi)),bp=new Pipe(bi,new PipedOutputStream(ai));
            Observer ao=new Observer(),bo=new Observer();
            CocStreams sa=new CocStreams(RadioConfig.publicMesh("ble","A",a.port()),ao),sb=new CocStreams(RadioConfig.publicMesh("ble","B",b.port()),bo);
            try{check(sb.accept(bp),"receiver needs no prior scan");check(sa.dial("B",()->ap),"dial accepted");
                Socket ac=a.get(),bc=b.get();byte[] payload=new byte[10000];new Random(7).nextBytes(payload);
                ac.getOutputStream().write(payload);byte[] read=new byte[payload.length];CocProtocol.readExact(bc.getInputStream(),read);check(Arrays.equals(payload,read),"A-to-B exact stream");
                bc.getOutputStream().write(new byte[]{1,2,3});byte[] reverse=new byte[3];CocProtocol.readExact(ac.getInputStream(),reverse);check(Arrays.equals(reverse,new byte[]{1,2,3}),"B-to-A exact stream");
                check(!sa.dial("B",()->null),"duplicate cannot replace healthy link");
                sa.close();waitFor(()->sb.size()==0);check(ap.closes.get()==1&&bp.closes.get()==1,"duplex stop cleanup");
            }finally{sa.close();sb.close();}
        }
    }
}
