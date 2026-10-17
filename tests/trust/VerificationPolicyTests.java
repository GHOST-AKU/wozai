package dev.ghost.nearbyim.storage;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

public final class VerificationPolicyTests {
    private static Method verify;
    private static int checks;
    private static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    private static boolean save(TrustPolicy policy,TrustPolicy.Authorization grant,AtomicInteger writes)throws Exception {
        return (Boolean)verify.invoke(policy,grant,(Runnable)writes::incrementAndGet);
    }
    public static void main(String[] args)throws Exception {
        try{verify=TrustPolicy.class.getMethod("verify",TrustPolicy.Authorization.class,Runnable.class);}
        catch(NoSuchMethodException missing){throw new AssertionError("Manual verification must share the ready/consent/revoke boundary",missing);}
        TrustPolicy policy=new TrustPolicy();AtomicInteger writes=new AtomicInteger();
        TrustPolicy.Authorization grant=policy.begin("peer","root",null,false,false);
        check(!save(policy,grant,writes),"no verification before local consent");
        policy.approve(grant,false);check(!save(policy,grant,writes),"no verification before ready");
        policy.ready(grant);check(save(policy,grant,writes)&&writes.get()==1,"explicit verification may pin a ready only-this-time session");
        policy.revoke("peer");check(!save(policy,grant,writes),"queued verification cannot restore revoked trust");
        TrustPolicy.Authorization closed=policy.begin("peer","root",null,true,true);policy.ready(closed);policy.cancel(closed);
        check(!save(policy,closed,writes),"closed session cannot mark verified");
        TrustPolicy.Authorization changed=policy.begin("peer","other","root",true,true);
        check(!save(policy,changed,writes),"changed identity cannot mark verified");
        check(writes.get()==1,"all denied paths leave storage unchanged");
        System.out.println("VerificationPolicyTests: "+checks+" consent/revoke/close checks passed");
    }
}
