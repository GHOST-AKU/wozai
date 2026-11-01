package org.nearbymesh.radio;

import android.app.*;
import android.content.Context;
import android.os.*;
import org.json.JSONObject;
import java.util.*;
import java.util.concurrent.*;

/** Internal foreground laboratory runner; no GUI, background service or permission bypass. */
public final class RadioLab extends Instrumentation implements RadioEvents {
    private final long began=SystemClock.elapsedRealtime();
    private HandlerThread thread;
    private Handler handler;
    private RadioAdapter adapter;
    private RadioConfig config;
    private Bundle arguments;
    private int events,terminalEvents,droppedEvents;
    private volatile boolean failed;
    private CountDownLatch stopSignal;
    @Override public void onCreate(Bundle args){super.onCreate(args);arguments=args==null?new Bundle():args;start();}
    @Override public void onStart(){
        try {
            Map<String,String> values=new HashMap<>();
            for(String key:arguments.keySet()) {Object value=arguments.get(key);if(!(value instanceof String))throw new IllegalArgumentException("argument_type");values.put(key,(String)value);}
            config=RadioConfig.parse(values);
        }catch(RuntimeException error){emit("run.error","","invalid_config",0,0);finish(Activity.RESULT_CANCELED,new Bundle());return;}
        thread=new HandlerThread("mesh-radio-callbacks");thread.start();handler=new Handler(thread.getLooper());
        CountDownLatch stop=new CountDownLatch(1);stopSignal=stop;
        handler.post(()->{
            try {
                Context context=getTargetContext();
                adapter="ble".equals(config.mode)?new BleAdapter(context,handler,config,this):new AwareAdapter(context,handler,config,this);
                emit("run.start",config.label,"hardware_validation_pending",0,0);
                adapter.start();
            }catch(Exception error){failed=true;emit("run.error","",error.getClass().getSimpleName(),0,0);stop.countDown();}
        });
        try {stop.await(config.seconds,TimeUnit.SECONDS);}catch(InterruptedException error){Thread.currentThread().interrupt();}
        CountDownLatch cleaned=new CountDownLatch(1);
        handler.post(()->{
            try{if(adapter!=null)adapter.close();}
            catch(RuntimeException error){failed=true;emit("cleanup.error","",error.getClass().getSimpleName(),0,0);}
            // close() may enqueue teardown on this same Handler; observe it before finishing.
            finally{if(!handler.post(cleaned::countDown))cleaned.countDown();}
        });
        try {if(!cleaned.await(5,TimeUnit.SECONDS)){failed=true;emit("cleanup.timeout","","pending_resources",0,0);}}catch(InterruptedException error){Thread.currentThread().interrupt();}
        thread.quitSafely();
        try{thread.join(3000);if(thread.isAlive()){failed=true;emit("cleanup.timeout","","handler_still_running",0,0);}}
        catch(InterruptedException error){failed=true;Thread.currentThread().interrupt();}
        emit("run.end",config.label,"device_acceptance_requires_logs",0,0);
        Bundle end=new Bundle();end.putString("stream","Radio experiment ended; inspect Rust authentication/path logs and physical topology.\n");
        finish(failed?Activity.RESULT_CANCELED:Activity.RESULT_OK,end);
    }
    @Override public synchronized void emit(String event,String peer,String reason,long tx,long rx){
        if("terminal_failure".equals(event)||"unsupported".equals(event)||"run.error".equals(event)||("ble_error".equals(event)&&"".equals(peer))) {
            failed=true;if(stopSignal!=null)stopSignal.countDown();
        }
        // Reserve a separate bounded allowance so traffic cannot hide final cleanup status.
        boolean terminal="terminal_failure".equals(event)||event.startsWith("run.")||event.startsWith("cleanup.");
        if(terminal?terminalEvents++>=8:events++>=2048){droppedEvents++;return;}
        try {
            JSONObject out=new JSONObject();out.put("schema",1);out.put("event",token(event));out.put("peer",token(peer));
            out.put("reason",token(reason));out.put("mode",config==null?"unknown":config.mode);
            out.put("dropped_events",droppedEvents);out.put("elapsed_ms",SystemClock.elapsedRealtime()-began);out.put("tx_bytes",Math.max(0,tx));out.put("rx_bytes",Math.max(0,rx));
            Bundle status=new Bundle();status.putString("stream",out.toString()+"\n");sendStatus(0,status);
        }catch(Exception ignored){ // Diagnostics must never expose a raw platform exception or payload.
        }
    }
    private String token(String text){return text!=null&&text.matches("[A-Za-z0-9_.:-]{0,80}")?text:"redacted";}
}
