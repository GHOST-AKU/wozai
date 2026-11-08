package org.nearbymesh.radio;

/** Fixed byte budget independent of transport fragment size; reads coalesce already queued data. */
final class BytePipe {
    private final byte[] bytes;
    private int head,count;
    private boolean closed;
    BytePipe(int capacity) {
        if(capacity<1)throw new IllegalArgumentException("pipe_capacity");
        bytes=new byte[capacity];
    }
    boolean offer(byte[] data) {return offer(data,0,data.length);}
    synchronized boolean offer(byte[] data,int offset,int size) {
        if(offset<0||size<0||offset>data.length-size)throw new IllegalArgumentException("pipe_range");
        if(closed||size>bytes.length-count)return false;
        boolean empty=count==0;
        int tail=(head+count)%bytes.length;
        int first=Math.min(size,bytes.length-tail);
        System.arraycopy(data,offset,bytes,tail,first);
        System.arraycopy(data,offset+first,bytes,0,size-first);
        count+=size;
        if(empty&&count>0)notifyAll();
        return true;
    }
    synchronized int read(byte[] target)throws InterruptedException {
        if(target.length==0)throw new IllegalArgumentException("empty_read");
        while(!closed&&count==0)wait();
        if(closed)return -1;
        int size=Math.min(count,target.length),first=Math.min(size,bytes.length-head);
        System.arraycopy(bytes,head,target,0,first);
        System.arraycopy(bytes,0,target,first,size-first);
        head=(head+size)%bytes.length;count-=size;
        return size;
    }
    synchronized void close(){closed=true;count=0;notifyAll();}
}
