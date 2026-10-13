package dev.ghost.nearbyim.core;

import java.lang.reflect.Method;
import java.util.UUID;

/** The displayed path must describe the current task, never guess LAN topology. */
public final class TransferPathTests {
    private static int checks;
    private static Method classify;
    public static void main(String[] args) throws Exception {
        try { classify=Class.forName("dev.ghost.nearbyim.core.TransferPath").getMethod("forAttachment",String.class,boolean.class,AttachmentRecord.class); }
        catch (ReflectiveOperationException missing) { throw new AssertionError("Transfer cards have no current-session path classification",missing); }
        for(String state:new String[]{"transferring","checking","verifying","awaitingReceipt","paused","interrupted","sourceUnavailable","unknown"}) {
            expect("LAN_UNCONFIRMED","lan",true,record(state,true));
            expect("BLUETOOTH","bluetooth",true,record(state,false));
            expect("DISCONNECTED","lan",false,record(state,true));
            expect("UNKNOWN","wifi",true,record(state,true));
        }
        for(String state:new String[]{"queued","preparing","offered"}) {
            expect("PENDING_LAN","lan",true,record(state,true));
            expect("PENDING_BLUETOOTH","bluetooth",true,record(state,true));
            expect("DISCONNECTED","bluetooth",false,record(state,true));
        }
        for(String state:new String[]{"received","delivered","failed","canceled","rejected"}) {
            expect("NONE","lan",true,record(state,state.equals("delivered")));
            expect("NONE","bluetooth",false,record(state,false));
        }
        expect("NONE","lan",true,null);
        Object lan=classify.invoke(null,"lan",true,record("transferring",true));
        String key=(String)lan.getClass().getMethod("key").invoke(lan);
        if(!"attachmentPathLanUnconfirmed".equals(key))throw new AssertionError("LAN must not claim direct Wi-Fi or a router from transport alone");
        checks++;
        System.out.println("Transfer path: "+checks+" checks passed (LAN topology unconfirmed; current session; queued/disconnected/terminal states)");
    }
    private static AttachmentRecord record(String state,boolean outgoing)throws Exception {
        AttachmentInfo info=AttachmentInfo.v2(UUID.randomUUID().toString(),"sample.bin","application/octet-stream",4096,"0".repeat(64),0);
        long transferred=state.equals("received")||state.equals("delivered")?4096:512;
        return new AttachmentRecord(info,outgoing,state,transferred);
    }
    private static void expect(String wanted,String transport,boolean ready,AttachmentRecord record)throws Exception {
        String actual=classify.invoke(null,transport,ready,record).toString();
        if(!wanted.equals(actual))throw new AssertionError("Expected "+wanted+" for "+transport+"/"+ready+"/"+(record==null?"none":record.state)+" but got "+actual);
        checks++;
    }
}
