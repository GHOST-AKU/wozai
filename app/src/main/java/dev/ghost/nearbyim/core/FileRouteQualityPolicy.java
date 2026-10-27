package dev.ghost.nearbyim.core;

/** Conservative decisions from acknowledged, comparable encrypted-link measurements. */
public final class FileRouteQualityPolicy {
    public static final long ROUND_NS=500_000_000L,ROUND_BYTES=4L*1024*1024,CACHE_NS=60_000_000_000L;
    public static final long STALL_NS=8_000_000_000L,RECOVERY_NS=30_000_000_000L,SETUP_NS=8_000_000_000L;
    private static final long MIN_SAMPLE_NS=100_000_000L,MIN_BYTES=128*1024;
    private FileRouteQualityPolicy(){}
    public static final class Sample {
        public final long bytes,nanos;public final boolean complete;
        public Sample(long bytes,long nanos,boolean complete){
            if(bytes<0||bytes>ROUND_BYTES||nanos<0)throw new IllegalArgumentException("Invalid link sample");
            this.bytes=bytes;this.nanos=nanos;this.complete=complete;
        }
        public boolean valid(){return complete&&bytes>=MIN_BYTES&&nanos>0;}
        public double lowerRate(){return valid()?bytes*1e9/Math.max(MIN_SAMPLE_NS,nanos):0;}
        public double upperRate(){return !valid()||nanos<MIN_SAMPLE_NS?Double.POSITIVE_INFINITY:bytes*1e9/nanos;}
    }
    public static boolean eligible(long activeNanos,long remainingBytes,double fileBytesPerSecond,boolean ioBusy){
        return !ioBusy&&activeNanos>=2_000_000_000L&&remainingBytes>0&&Double.isFinite(fileBytesPerSecond)
            &&fileBytesPerSecond>0&&remainingBytes/fileBytesPerSecond>=8;
    }
    public static boolean preferLan(Sample[] direct,Sample[] lan,long remainingBytes,long switchCostNanos){
        return preferLan(direct,lan,remainingBytes,switchCostNanos,Double.POSITIVE_INFINITY);
    }
    public static boolean preferLan(Sample[] direct,Sample[] lan,long remainingBytes,long switchCostNanos,double capacity){
        return evaluate(direct,lan,remainingBytes,switchCostNanos,capacity).preferLan();
    }
    public record Decision(boolean preferLan,String reason,double directBytesPerSecond,double lanBytesPerSecond,double netSavingsSeconds){}
    /** Do not pause a file when even an infinitely fast candidate cannot repay the assessment. */
    public static boolean assessmentWorthwhile(long remaining,double rate,long switchCostNanos){
        return remaining>0&&Double.isFinite(rate)&&rate>0&&switchCostNanos>=0
            &&remaining/rate>switchCostNanos/1e9+2+2;
    }
    public static Decision evaluate(Sample[] direct,Sample[] lan,long remaining,long cost,double capacity){
        if(!(capacity>0)||remaining<=0||cost<0)return new Decision(false,"invalid_budget",0,0,0);
        if(direct==null||lan==null||direct.length!=2||lan.length!=2)return new Decision(false,"samples_incomplete",0,0,0);
        double fastestDirect=0,slowestLan=Double.POSITIVE_INFINITY;
        for(int i=0;i<2;i++){
            if(direct[i]==null||lan[i]==null||!direct[i].valid()||!lan[i].valid())return new Decision(false,"samples_incomplete",0,0,0);
            fastestDirect=Math.max(fastestDirect,direct[i].upperRate());slowestLan=Math.min(slowestLan,lan[i].lowerRate());
        }
        if(!Double.isFinite(fastestDirect))return new Decision(false,"direct_sample_too_short",0,slowestLan,0);
        double directRate=Math.min(capacity,fastestDirect),lanRate=Math.min(capacity,slowestLan);
        double gain=remaining/directRate-remaining/lanRate-cost/1e9;
        // Compare the complete ranges, not two conveniently paired moments on a fluctuating radio.
        if(lanRate<directRate*1.25)return new Decision(false,"lan_margin_insufficient",directRate,lanRate,gain);
        boolean useful=Double.isFinite(gain)&&gain>=2;
        return new Decision(useful,useful?"lan_faster":"net_gain_insufficient",directRate,lanRate,gain);
    }
}
