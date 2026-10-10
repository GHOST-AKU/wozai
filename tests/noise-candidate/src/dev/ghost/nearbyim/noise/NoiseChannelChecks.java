package dev.ghost.nearbyim.noise;

import dev.ghost.nearbyim.core.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.lang.reflect.Field;

/** Controlled socket tests of candidate NIM4 binding, framing and failure handling. */
public final class NoiseChannelChecks {
    private static int checks;
    private interface Action {void run()throws Exception;}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
    private static void rejects(Action action)throws Exception {try{action.run();}catch(IOException expected){checks++;return;}throw new AssertionError("Invalid channel operation accepted");}
    public static synchronized int run()throws Exception {
        checks=0;
        bidirectional();bulkRecordReads();fragmentedRecords();invalidPin();forgedRoot();mismatchedStatic();invalidClaim();badTag();replay();oldSession();oversize();legacy();truncation();closeDuringHandshake();
        return checks;
    }
    public static void main(String[] args)throws Exception {System.out.println("NIM4 candidate: "+run()+" root-binding/record checks passed (not client/radio acceptance)");}
    private static void bidirectional()throws Exception {
        try(Harness h=new Harness(null)) {
            check(h.a.remoteHello()==null&&h.a.remotePublicKey()==null,"Unverified identity escaped before handshake");
            rejects(()->h.a.write(new byte[0]));h.start();
            check(h.a.remoteHello().id.equals(h.bId)&&h.b.remoteHello().id.equals(h.aId),"Authenticated UUID changed");
            check(h.a.remotePublicKey().equals(h.bRoot.publicKey()),"Persistent P-256 root changed");
            check(h.a.connectionGeneration().equals(h.b.connectionGeneration()),"Endpoints disagree on connection generation");
            byte[] secret="Controlled-test-secret: file bytes must not be visible".getBytes("UTF-8");int before=h.aOutput.size();
            h.a.write(secret);check(Arrays.equals(h.b.read(),secret),"Outgoing plaintext changed");
            byte[] record=h.aOutput.since(before);check(indexOf(record,secret)<0,"Plaintext was written to the socket");
            byte[] large=new byte[48*1024];new Random(32).nextBytes(large);h.b.write(large);check(Arrays.equals(h.a.read(),large),"Reverse maximum record changed");
            rejects(()->h.a.write(new byte[48*1024+1]));h.a.close();rejects(()->h.a.write(secret));
        }
    }
    /** A socket read may have nontrivial platform cost; never parse record headers byte by byte. */
    private static void bulkRecordReads()throws Exception {
        try(Harness h=new Harness(null)) {
            h.start();byte[] plain=new byte[32768];new Random(311).nextBytes(plain);
            int before=h.aOutput.size();h.a.write(plain);byte[] encoded=h.aOutput.since(before);
            h.bInput.inject(encoded);h.bInput.scalarReads=h.bInput.bulkReads=0;h.bInput.delayMs=2;
            long begin=System.nanoTime();check(Arrays.equals(h.b.read(),plain),"Batched header read changed encrypted content");
            System.out.println("Controlled 2 ms read-call cost, 32 KiB Noise record: scalar="+h.bInput.scalarReads+", bulk="+h.bInput.bulkReads+", ms="+(System.nanoTime()-begin)/1e6+" (not phone throughput)");
            check(h.bInput.scalarReads==0,"Noise record header performs one socket read per byte: "+h.bInput.scalarReads);
            check(h.bInput.bulkReads<=3,"Complete injected record needs excessive read calls: "+h.bInput.bulkReads);
            String diagnostic;
            try{diagnostic=(String)NoiseRecordChannel.class.getMethod("diagnostics").invoke(h.b);}
            catch(NoSuchMethodException missing){throw new AssertionError("Encrypted socket read has no stage timing report",missing);}
            check(diagnostic.contains("socket_read")&&diagnostic.contains("decrypt"),"Encrypted read report missing socket/decrypt stages");
            check(!diagnostic.contains(h.aRoot.publicKey())&&!diagnostic.contains(h.aId),"Timing report leaked device identity");
        }
    }
    private static void fragmentedRecords()throws Exception {
        for(int fragment:new int[]{1,3,7})try(Harness h=new Harness(null)) {
            h.start();byte[] plain=new byte[193];new Random(fragment).nextBytes(plain);int before=h.aOutput.size();h.a.write(plain);
            h.bInput.inject(h.aOutput.since(before));h.bInput.fragment=fragment;
            check(Arrays.equals(h.b.read(),plain),"Partial header/payload reads corrupted Noise record: "+fragment);
        }
    }
    private static void invalidPin()throws Exception {
        try(Harness h=new Harness(DeviceIdentity.generate().publicKey())) {rejects(h::start);check(h.a.remoteHello()==null,"Changed root accepted against a pin");}
    }
    private static void forgedRoot()throws Exception {
        try(Harness h=new Harness(null)) {
            byte[] claim=claim(h.a),old=Base64.getDecoder().decode(h.aRoot.publicKey()),replacement=Base64.getDecoder().decode(DeviceIdentity.generate().publicKey());
            int position=indexOf(claim,old);check(position>=0&&old.length==replacement.length,"Root forgery fixture failed");
            System.arraycopy(replacement,0,claim,position,replacement.length);rejects(h::start);
            check(h.b.remotePublicKey()==null,"Root signature forgery escaped verification");
        }
    }
    private static void mismatchedStatic()throws Exception {
        try(Harness h=new Harness(null)) {
            // The last 48 bytes are static key and strict file/record capabilities.
            byte[] claim=claim(h.a);claim[claim.length-48]^=1;rejects(h::start);
        }
    }
    private static void invalidClaim()throws Exception {
        try(Harness h=new Harness(null)) {byte[] claim=claim(h.a);claim[1]=1;rejects(h::start);}
        try(Harness h=new Harness(null)) {byte[] claim=claim(h.a);claim[claim.length-1]^=1;rejects(h::start);}
    }
    private static void badTag()throws Exception {
        try(Harness h=new Harness(null)) {h.start();int before=h.aOutput.size();h.a.write(new byte[]{1,2,3,4,5});byte[] record=h.aOutput.since(before);record[record.length-1]^=1;h.bInput.inject(record);rejects(()->h.b.read());check(!h.b.verified(),"Bad tag left a usable channel");}
    }
    private static void replay()throws Exception {
        try(Harness h=new Harness(null)) {h.start();byte[] record=record(h);h.bInput.inject(record);rejects(()->h.b.read());}
    }
    private static void oldSession()throws Exception {
        byte[] record;String generation;Identities identities=new Identities();
        try {
            try(Harness h=new Harness(identities,null,false)) {h.start();record=record(h);generation=h.a.connectionGeneration();}
            try(Harness h=new Harness(identities,null,false)) {h.start();check(!generation.equals(h.a.connectionGeneration()),"Same roots/static keys reused a connection generation");h.bInput.inject(record);rejects(()->h.b.read());}
        }finally{identities.close();}
    }
    private static void oversize()throws Exception {
        try(Harness h=new Harness(null)) {h.start();ByteArrayOutputStream b=new ByteArrayOutputStream();new DataOutputStream(b).writeInt(Integer.MAX_VALUE);h.bInput.inject(b.toByteArray());rejects(()->h.b.read());}
    }
    private static void legacy()throws Exception {
        try(Harness h=new Harness(null)) {h.start();ByteArrayOutputStream b=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(b);out.writeInt(32);out.writeInt(0x4e494d33);out.writeByte(3);out.writeByte(3);h.bInput.inject(b.toByteArray());rejects(()->h.b.read());}
    }
    private static void truncation()throws Exception {
        try(Harness h=new Harness(null)) {h.start();byte[] record=record(h);h.a.close();h.bInput.inject(Arrays.copyOf(record,record.length-1));rejects(()->h.b.read());}
    }
    private static void closeDuringHandshake()throws Exception {
        try(Harness h=new Harness(null)) {
            Future<?> pending=h.executor.submit(()->{try{h.a.establish();throw new AssertionError("Closed handshake accepted");}catch(IOException expected){}});
            h.a.close();pending.get(2,TimeUnit.SECONDS);check(!h.a.verified(),"Close during handshake kept keys active");
        }
    }
    private static byte[] claim(NoiseRecordChannel channel)throws Exception {Field field=NoiseRecordChannel.class.getDeclaredField("localClaim");field.setAccessible(true);return (byte[])field.get(channel);}
    private static byte[] record(Harness h)throws Exception {int before=h.aOutput.size();byte[] bytes={1,2,3,4,5};h.a.write(bytes);check(Arrays.equals(h.b.read(),bytes),"Fixture record failed");return h.aOutput.since(before);}
    private static int indexOf(byte[] haystack,byte[] needle) {outer:for(int i=0;i<=haystack.length-needle.length;i++){for(int j=0;j<needle.length;j++)if(haystack[i+j]!=needle[j])continue outer;return i;}return -1;}
    private static final class Harness implements AutoCloseable {
        final DeviceIdentity aRoot,bRoot;
        final String aId,bId;
        final ExecutorService executor=Executors.newFixedThreadPool(2,r->{Thread t=new Thread(r,"noise-channel-check");t.setDaemon(true);return t;});
        final NoiseRecordChannel a,b;final RecordingOutput aOutput;final InjectedInput bInput;
        Harness(String expectedRoot)throws Exception {this(new Identities(),expectedRoot,true);}
        Harness(Identities identities,String expectedRoot,boolean ownIdentities)throws Exception {
            aRoot=identities.aRoot;bRoot=identities.bRoot;aId=identities.aId;bId=identities.bId;
            Socket x,y;try(ServerSocket server=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){x=new Socket("127.0.0.1",server.getLocalPort());y=server.accept();}
            x.setSoTimeout(5000);y.setSoTimeout(5000);aOutput=new RecordingOutput(x.getOutputStream());bInput=new InjectedInput(y.getInputStream());
            byte[] ka=identities.aNoise.clone(),kb=identities.bNoise.clone();
            try {a=new NoiseRecordChannel(connection(x,x.getInputStream(),aOutput),true,aRoot,aId,"A",ka,bId,expectedRoot);
                b=new NoiseRecordChannel(connection(y,bInput,y.getOutputStream()),false,bRoot,bId,"B",kb,aId,null);
            }finally{Arrays.fill(ka,(byte)0);Arrays.fill(kb,(byte)0);if(ownIdentities)identities.close();}
        }
        void start()throws Exception {
            Future<?> first=executor.submit(()->{a.establish();return null;}),second=executor.submit(()->{b.establish();return null;});
            try{first.get(8,TimeUnit.SECONDS);second.get(8,TimeUnit.SECONDS);}
            catch(ExecutionException e){Throwable cause=e.getCause();if(cause instanceof IOException)throw (IOException)cause;throw e;}
        }
        public void close()throws IOException {a.close();b.close();executor.shutdownNow();}
    }
    private static final class Identities {
        final DeviceIdentity aRoot=DeviceIdentity.generate(),bRoot=DeviceIdentity.generate();
        final String aId=UUID.randomUUID().toString(),bId=UUID.randomUUID().toString();
        final byte[] aNoise=new byte[32],bNoise=new byte[32];
        Identities()throws Exception {java.security.SecureRandom random=new java.security.SecureRandom();random.nextBytes(aNoise);random.nextBytes(bNoise);}
        void close(){Arrays.fill(aNoise,(byte)0);Arrays.fill(bNoise,(byte)0);}
    }
    private static StreamConnection connection(Socket socket,InputStream input,OutputStream output) {return new StreamConnection(){public InputStream input(){return input;}public OutputStream output(){return output;}public String label(){return "Controlled NIM4 socket";}public void close()throws IOException{socket.close();}};}
    private static final class RecordingOutput extends OutputStream {
        final OutputStream delegate;final ByteArrayOutputStream copy=new ByteArrayOutputStream();
        RecordingOutput(OutputStream delegate){this.delegate=delegate;}
        public synchronized void write(int b)throws IOException{delegate.write(b);copy.write(b);}
        public synchronized void write(byte[] b,int off,int len)throws IOException{delegate.write(b,off,len);copy.write(b,off,len);}
        public void flush()throws IOException{delegate.flush();}
        synchronized int size(){return copy.size();}synchronized byte[] since(int position){return Arrays.copyOfRange(copy.toByteArray(),position,copy.size());}
    }
    private static final class InjectedInput extends InputStream {
        final InputStream delegate;byte[] injected=new byte[0];int offset,scalarReads,bulkReads,delayMs,fragment=Integer.MAX_VALUE;
        private void pause()throws IOException{if(delayMs>0)try{Thread.sleep(delayMs);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new InterruptedIOException();}}
        InjectedInput(InputStream delegate){this.delegate=delegate;}
        synchronized void inject(byte[] bytes){injected=bytes;offset=0;}
        public synchronized int read()throws IOException{scalarReads++;pause();return offset<injected.length?injected[offset++]&255:delegate.read();}
        public synchronized int read(byte[] bytes,int start,int size)throws IOException {bulkReads++;pause();size=Math.min(size,fragment);if(offset>=injected.length)return delegate.read(bytes,start,size);int n=Math.min(size,injected.length-offset);System.arraycopy(injected,offset,bytes,start,n);offset+=n;return n;}
    }
}
