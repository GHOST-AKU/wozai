package dev.ghost.nearbyim.core;

/** Fixed network samples exercise policy without clocks, sockets, disk, or radio noise. */
public final class FileRouteQualityPolicyTests {
    public static void main(String[] args)throws Exception {
        Class<?> policy;
        try{policy=Class.forName("dev.ghost.nearbyim.core.FileRouteQualityPolicy");}
        catch(ClassNotFoundException missing){throw new AssertionError("FILE routing has no measured-quality decision policy",missing);}
        Class<?> sample=Class.forName(policy.getName()+"$Sample");
        var constructor=sample.getConstructor(long.class,long.class,boolean.class);
        var prefer=policy.getMethod("preferLan",java.lang.reflect.Array.newInstance(sample,0).getClass(),java.lang.reflect.Array.newInstance(sample,0).getClass(),long.class,long.class);
        Object direct=java.lang.reflect.Array.newInstance(sample,2),lan=java.lang.reflect.Array.newInstance(sample,2);
        for(int i=0;i<2;i++){java.lang.reflect.Array.set(direct,i,constructor.newInstance(1048576L,250_000_000L,true));java.lang.reflect.Array.set(lan,i,constructor.newInstance(4194304L,250_000_000L,true));}
        check((Boolean)prefer.invoke(null,direct,lan,64L*1048576,1_000_000_000L),"Repeated 4 vs 16 MiB/s evidence should select LAN with useful remaining work");
        check(!(Boolean)prefer.invoke(null,lan,direct,64L*1048576,0L),"Faster Direct must remain selected");
        check(!(Boolean)prefer.invoke(null,direct,lan,1048576L,0L),"Tiny remaining transfer cannot recover migration cost");
        check(!(Boolean)prefer.invoke(null,direct,lan,64L*1048576,20_000_000_000L),"Large prefix verification cost must veto an uneconomic migration");
        java.lang.reflect.Array.set(lan,1,constructor.newInstance(1048576L,240_000_000L,true));
        check(!(Boolean)prefer.invoke(null,direct,lan,64L*1048576,0L),"One fast round is insufficient; both need at least 25 percent improvement");
        java.lang.reflect.Array.set(lan,1,constructor.newInstance(4194304L,250_000_000L,false));
        check(!(Boolean)prefer.invoke(null,direct,lan,64L*1048576,0L),"Partial or unacknowledged samples are not quality evidence");
        java.lang.reflect.Array.set(lan,1,constructor.newInstance(4194304L,250_000_000L,true));
        java.lang.reflect.Array.set(direct,0,constructor.newInstance(4194304L,10_000_000L,true));
        check(!(Boolean)prefer.invoke(null,direct,lan,64L*1048576,0L),"Very short Direct samples cannot be treated as a precise low speed");
        var eligible=policy.getMethod("eligible",long.class,long.class,double.class,boolean.class);
        check((Boolean)eligible.invoke(null,2_000_000_000L,64L*1048576,4.0*1048576,false),"Sustained long transfer may be assessed");
        check(!(Boolean)eligible.invoke(null,1_000_000_000L,64L*1048576,4.0*1048576,false),"Do not probe before two seconds of real transfer");
        check(!(Boolean)eligible.invoke(null,2_000_000_000L,1048576L,4.0*1048576,false),"Do not delay a nearly finished file");
        check(!(Boolean)eligible.invoke(null,2_000_000_000L,64L*1048576,4.0*1048576,true),"Storage or verification busy is not wireless degradation");
        FileRouteQualityPolicy.Sample[] variedDirect={new FileRouteQualityPolicy.Sample(1048576,200_000_000,true),new FileRouteQualityPolicy.Sample(2097152,200_000_000,true)};
        FileRouteQualityPolicy.Sample[] variedLan={new FileRouteQualityPolicy.Sample(2306867,200_000_000,true),new FileRouteQualityPolicy.Sample(3145728,200_000_000,true)};
        check(!FileRouteQualityPolicy.preferLan(variedDirect,variedLan,1024L*1048576,0),"Fluctuating round ranges must not be sold as a reliable 25 percent gain");
        check(!FileRouteQualityPolicy.assessmentWorthwhile(32L*1048576,4.0*1048576,5_000_000_000L),"Do not spend more than the remaining transfer time on assessment and switching");
        check(FileRouteQualityPolicy.assessmentWorthwhile(64L*1048576,4.0*1048576,1_000_000_000L),"Long transfers retain a useful comparison opportunity");
        var result=FileRouteQualityPolicy.evaluate(variedDirect,variedLan,1024L*1048576,0,Double.POSITIVE_INFINITY);
        check(result.reason().equals("lan_margin_insufficient")&&result.directBytesPerSecond()>0&&result.lanBytesPerSecond()>0,"Retain decision exposes conservative rates and a precise reason");
        result=FileRouteQualityPolicy.evaluate(variedDirect,variedLan,1024L*1048576,0,1048576);
        check(!result.preferLan(),"An I/O-limited candidate cannot provide a radio-only speedup");
        System.out.println("FILE quality policy: hysteresis, repeated evidence, short samples, useful remaining work and local-I/O guard passed");
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
