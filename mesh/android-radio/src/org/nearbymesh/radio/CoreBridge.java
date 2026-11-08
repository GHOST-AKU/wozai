package org.nearbymesh.radio;

import java.io.*;
import java.net.*;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/** Device-local IPC only. Every byte sent to Sink must cross the selected physical radio. */
public final class CoreBridge implements AutoCloseable {
    public interface Sink {void send(byte[] bytes)throws IOException;void close();}
    public static final int MAX_CHUNK=4096,MAX_QUEUED=8;
    private final int port;
    private final Sink sink;
    private final Runnable onClosed;
    private final BytePipe ingress=new BytePipe(MAX_QUEUED*MAX_CHUNK);
    private final AtomicBoolean closed=new AtomicBoolean();
    private Socket socket;
    private boolean started;
    private Thread reader,writer;
    public CoreBridge(int corePort,Sink sink,Runnable onClosed) {
        if(corePort<1||corePort>65535||sink==null||onClosed==null)throw new IllegalArgumentException("bridge_config");
        this.port=corePort;this.sink=sink;this.onClosed=onClosed;
    }
    public synchronized void start()throws IOException {
        if(closed.get()||started)throw new IOException("bridge_closed_or_started");
        started=true;socket=new Socket();
        try {
            socket.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),port),500);
            socket.setTcpNoDelay(true);
            reader=new Thread(this::readCore,"mesh-core-reader");
            writer=new Thread(this::writeCore,"mesh-core-writer");
            reader.setDaemon(true);writer.setDaemon(true);reader.start();writer.start();
        }catch(IOException error){close();throw error;}
    }
    public boolean receive(byte[] bytes) {return receive(bytes,0,bytes==null?0:bytes.length);}
    public boolean receive(byte[] bytes,int offset,int count) {
        if(closed.get())return false;
        if(bytes==null||offset<0||count<1||count>MAX_CHUNK||offset>bytes.length-count||!ingress.offer(bytes,offset,count)) {close();return false;}
        if(closed.get()){ingress.close();return false;}
        return true;
    }
    private void readCore() {
        try {
            InputStream input=socket.getInputStream();byte[] bytes=new byte[MAX_CHUNK];
            while(!closed.get()) {
                int count=input.read(bytes);if(count<0)break;
                if(count>0)sink.send(Arrays.copyOf(bytes,count));
            }
        }catch(IOException ignored) { // Link failure is propagated by onClosed, never a delivery acknowledgement.
        }finally{close();}
    }
    private void writeCore() {
        try {
            OutputStream output=socket.getOutputStream();
            byte[] bytes=new byte[MAX_CHUNK];
            while(!closed.get()) {int count=ingress.read(bytes);if(count<0)break;output.write(bytes,0,count);}
        }catch(IOException ignored) {
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
        finally{close();}
    }
    @Override public synchronized void close() {
        if(!closed.compareAndSet(false,true))return;
        ingress.close();
        if(socket!=null)try{socket.close();}catch(IOException ignored){}
        if(reader!=null)reader.interrupt();if(writer!=null)writer.interrupt();
        try{sink.close();}finally{onClosed.run();}
    }
}
