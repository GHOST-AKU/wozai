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
        if(!(capacity>0))return false;
        if(direct==null||lan==null||direct.length!=2||lan.length!=2||remainingBytes<=0||switchCostNanos<0)return false;
        double fastestDirect=0,slowestLan=Double.POSITIVE_INFINITY;
        for(int i=0;i<2;i++){
            if(direct[i]==null||lan[i]==null||!direct[i].valid()||!lan[i].valid())return false;
            if(lan[i].lowerRate()<direct[i].upperRate()*1.25)return false;
            fastestDirect=Math.max(fastestDirect,direct[i].upperRate());slowestLan=Math.min(slowestLan,lan[i].lowerRate());
        }
        double gainSeconds=remainingBytes/Math.min(capacity,fastestDirect)-remainingBytes/Math.min(capacity,slowestLan)-switchCostNanos/1e9;
        return Double.isFinite(gainSeconds)&&gainSeconds>=2;
    }
}
