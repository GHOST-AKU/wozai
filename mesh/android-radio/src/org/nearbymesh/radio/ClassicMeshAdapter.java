package org.nearbymesh.radio;

import android.bluetooth.*;
import android.content.Context;
import android.os.Handler;
import java.io.*;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Passive RFCOMM compatibility ingress; CoC remains the Android discovery/data path. */
public final class ClassicMeshAdapter implements RadioAdapter {
    public static final UUID SERVICE = UUID.fromString("27a34e22-69d1-4d97-93a4-12f231df9ab8");
    private static final ThreadPoolExecutor ACCEPT = CocRuntime.pool(1, "mesh-rfcomm-accept");
    private static final ThreadPoolExecutor BIND = CocRuntime.pool(1, "mesh-rfcomm-bind");
    private static final ThreadPoolExecutor IO = CocRuntime.pool(2, "mesh-rfcomm-io");
    private final Context context;
    private final Handler handler;
    private final RadioEvents events;
    private final CocStreams streams;
    private final Semaphore capacity = new Semaphore(2);
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile BluetoothServerSocket listener;
    private Future<?> accept;
    private int failures;
    private volatile long epoch;
    private CocRuntime.Ticket deadline;
    public ClassicMeshAdapter(Context context, Handler handler, RadioConfig config, RadioEvents events) {
        this.context=context; this.handler=handler; this.events=events;
        streams=new CocStreams(config, new CocStreams.Observer() {
            public void event(String event,String peer,String reason,long tx,long rx) {
                if (!closed.get()) events.emit(event.replace("ble_", "classic_"),peer,reason,tx,rx);
            }
            public void progress(CocStreams.Link link) { }
            public void capacityChanged() { }
        }, IO, CocRuntime.TIME);
    }
    @Override public void start() { bind(); }
    @android.annotation.SuppressLint("MissingPermission") // Owner checks Bluetooth permissions; revocation is caught.
    private synchronized void bind() {
        if (closed.get() || listener!=null) return;
        final long token=++epoch;
        deadline=CocRuntime.TIME.after(15000, () -> {
            if (!closed.get() && epoch==token && listener == null) events.emit("classic_unavailable","","bind_timeout",0,0);
        });
        try { BIND.execute(() -> {
            try {
                BluetoothManager manager=context.getSystemService(BluetoothManager.class);
                BluetoothAdapter adapter=manager==null?null:manager.getAdapter();
                if (adapter==null || !adapter.isEnabled()) throw new IOException("radio_unavailable");
                BluetoothServerSocket server=adapter.listenUsingInsecureRfcommWithServiceRecord("Nearby Mesh", SERVICE);
                synchronized (this) {
                    if (closed.get() || epoch!=token) { server.close(); return; }
                    listener=server;
                    if (deadline!=null) deadline.cancel();
                    accept=ACCEPT.submit(() -> receive(server,token));
                }
                events.emit("classic_ready","","rfcomm_listening",0,0);
            } catch (IOException | RuntimeException failure) { retry(token); }
        }); } catch (RejectedExecutionException busy) { retry(token); }
    }
    private void receive(BluetoothServerSocket server,long token) {
        try {
            while (!closed.get() && epoch==token) {
                // Two IO workers and shared Core limits bound compatibility neighbors.
                capacity.acquire();
                BluetoothSocket socket;
                try { socket=server.accept(); }
                catch (IOException | RuntimeException error) { capacity.release(); throw error; }
                if (closed.get() || epoch!=token) { try { socket.close(); } finally { capacity.release(); } break; }
                AtomicBoolean released=new AtomicBoolean();
                streams.accept(new CocStreams.Socket() {
                    public void connect() { }
                    public InputStream input() throws IOException { return socket.getInputStream(); }
                    public OutputStream output() throws IOException { return socket.getOutputStream(); }
                    public void close() { if (!released.compareAndSet(false,true)) return; try { socket.close(); } catch (IOException ignored) { } finally { capacity.release(); } }
                });
            }
        } catch (IOException | InterruptedException | RuntimeException failure) { retry(token); }
    }
    private synchronized void retry(long token) {
        if (epoch!=token) return;
        final long retired=++epoch;
        if (deadline!=null) deadline.cancel();
        if (closed.get()) return;
        if (listener!=null) try { listener.close(); } catch (IOException ignored) { }
        listener=null;
        long delay=failures++ >= 3 ? 60000 : 1000L << Math.min(failures-1,2);
        if (failures>=4) failures=0;
        events.emit("classic_retry","","listener_recovery",0,0);
        handler.postDelayed(() -> { if (epoch==retired) bind(); },delay);
    }
    @Override public synchronized void close() {
        if (!closed.compareAndSet(false,true)) return;
        ++epoch;
        if (deadline!=null) deadline.cancel();
        if (listener!=null) try { listener.close(); } catch (IOException ignored) { }
        if (accept!=null) accept.cancel(true);
        streams.close();
    }
}
