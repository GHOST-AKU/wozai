package dev.ghost.nearbyim.core;

import java.io.*;
import java.util.*;
import java.util.function.Consumer;

/** Bounded queues including the active write; preserve file FIFO and prioritize chat. */
public final class FairRecordWriter implements AutoCloseable {
    public static final long FILE_BUDGET=9L*1024*1024,CONTROL_BUDGET=1024*1024;
    public static final int ENTRY_OVERHEAD=256;
    public interface Write {void run()throws IOException;}
    private static final class Entry {final Write operation;final long charge;final boolean file;Entry(Write operation,long charge,boolean file){this.operation=operation;this.charge=charge;this.file=file;}}
    private final Object lock=new Object();
    private final ArrayDeque<Entry> files=new ArrayDeque<>(),controls=new ArrayDeque<>();
    private final Consumer<Throwable> failure;
    private long fileBytes,controlBytes;
    private boolean closed;
    private Thread worker;
    public FairRecordWriter(Consumer<Throwable> failure){this.failure=Objects.requireNonNull(failure);}
    /** retainedBytes covers captured payloads; fixed entry overhead is added internally. */
    public boolean submit(Write operation,int retainedBytes,boolean file) {
        Objects.requireNonNull(operation);if(retainedBytes<0||retainedBytes>65536)return false;long charge=retainedBytes+ENTRY_OVERHEAD;
        synchronized(lock) {
            if(closed||file&&(files.size()>=512||charge>FILE_BUDGET-fileBytes)||!file&&(controls.size()>=64||charge>CONTROL_BUDGET-controlBytes))return false;
            Entry entry=new Entry(operation,charge,file);if(file){files.addLast(entry);fileBytes+=charge;}else{controls.addLast(entry);controlBytes+=charge;}
            if(worker==null){worker=new Thread(this::run,"nearby-writer");worker.setDaemon(true);worker.start();}
            lock.notifyAll();return true;
        }
    }
    public long pendingBytes(){synchronized(lock){return fileBytes+controlBytes;}}
    private void run() {
        int controlBurst=0;
        while(true) {
            Entry entry;
            synchronized(lock) {
                while(!closed&&files.isEmpty()&&controls.isEmpty())try{lock.wait();}catch(InterruptedException e){if(closed)return;}
                if(closed)return;
                if(!controls.isEmpty()&&(files.isEmpty()||controlBurst<8)){entry=controls.removeFirst();controlBurst++;}
                else{entry=files.removeFirst();controlBurst=0;}
            }
            try {entry.operation.run();}
            catch(IOException|RuntimeException error) {
                boolean report;synchronized(lock){report=!closed;}close();if(report)failure.accept(error);return;
            }finally{synchronized(lock){if(entry.file)fileBytes-=entry.charge;else controlBytes-=entry.charge;}}
        }
    }
    public void close() {
        synchronized(lock) {
            if(closed)return;closed=true;
            for(Entry entry:files)fileBytes-=entry.charge;for(Entry entry:controls)controlBytes-=entry.charge;
            files.clear();controls.clear();lock.notifyAll();if(worker!=null&&worker!=Thread.currentThread())worker.interrupt();
        }
    }
}
