package org.nearbymesh.radio;
import java.util.*;
import java.util.concurrent.*;
public final class BytePipeTest {
    static void require(boolean value){if(!value)throw new AssertionError();}
    public static void main(String[] args)throws Exception{
        BytePipe pipe=new BytePipe(8);byte[] first={1,2,3,4,5};require(pipe.offer(first));first[0]=99;
        byte[] got=new byte[3];require(pipe.read(got)==3);require(Arrays.equals(got,new byte[]{1,2,3}));
        require(pipe.offer(new byte[]{6,7,8,9,10}));require(!pipe.offer(new byte[]{11,12}));
        byte[] rest=new byte[8];require(pipe.read(rest)==7);require(Arrays.equals(Arrays.copyOf(rest,7),new byte[]{4,5,6,7,8,9,10}));
        // Different input fragmentation must preserve the same stream, including wraparound.
        byte[] payload=new byte[4096];new Random(3).nextBytes(payload);BytePipe stream=new BytePipe(32768);
        for(int at=0;at<payload.length;at+=20)require(stream.offer(Arrays.copyOfRange(payload,at,Math.min(payload.length,at+20))));
        byte[] combined=new byte[4096];require(stream.read(combined)==4096);require(Arrays.equals(payload,combined));
        ExecutorService executor=Executors.newFixedThreadPool(2);
        try{
            Future<Integer> a=executor.submit(()->stream.read(new byte[1]));
            Future<Integer> b=executor.submit(()->stream.read(new byte[1]));
            stream.close();require(a.get(2,TimeUnit.SECONDS)==-1&&b.get(2,TimeUnit.SECONDS)==-1);
            require(!stream.offer(new byte[]{1}));stream.close();
        }finally{executor.shutdownNow();}
        require(pipe.offer(new byte[]{99,20,21,99},1,2));
        byte[] range=new byte[2];require(pipe.read(range)==2);require(Arrays.equals(range,new byte[]{20,21}));
        pipe.close();require(pipe.read(rest)==-1);
        System.out.println("BytePipeTest: PASS");
    }
}
