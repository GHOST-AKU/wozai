package dev.ghost.nearbyim.core;

import java.io.*;
import java.lang.reflect.*;
import java.util.*;

/** File history owns its route; chat transport and readiness cannot rewrite it. */
public final class TransferPathTests {
    private static int checks;
    private static Method decorate;
    public static void main(String[] args)throws Exception {
        try {decorate=AttachmentRecord.class.getMethod("withRoute",String.class);}
        catch(NoSuchMethodException missing){throw new AssertionError("Attachment history does not persist its actual file route",missing);}
        for(String state:new String[]{"transferring","queued","preparing","offered","paused","checking","verifying","awaitingReceipt","received","delivered","failed","canceled","rejected","interrupted","sourceUnavailable","unknown"}) {
            for(String route:new String[]{"wifi-direct","lan","bluetooth"}) {
                AttachmentRecord source=record(state,true),record=route(source,route);
                check(source!=record&&routeValue(source).isEmpty(),"Decoration mutates source history");
                check(record.info==source.info&&record.outgoing==source.outgoing&&record.transferred==source.transferred&&record.state.equals(source.state),"Decoration changes attachment fields");
                AttachmentRecord decoded=AttachmentRecord.decode(record.encode());
                check(decoded.equals(record)&&decoded.hashCode()==record.hashCode()&&routeValue(decoded).equals(route),"Route round trip loses history fields");
                check(routeValue(record.recovered()).equals(route),"Crash recovery loses the recorded route");
                String wanted=route.equals("wifi-direct")?"WIFI_DIRECT":route.equals("lan")?"LAN_UNCONFIRMED":"BLUETOOTH";
                for(String chat:new String[]{"lan","bluetooth","wifi_direct",""})for(boolean ready:new boolean[]{true,false})check(TransferPath.forAttachment(chat,ready,decoded).toString().equals(wanted),"Chat mode/readiness rewrites "+state+" route "+route);
                check(!record.equals(source),"History equality ignores changed route");
                check(routeValue(route(record,"" )).equals(route),"Pending coordinator update erases actual route");
            }
        }
        AttachmentRecord plain=record("transferring",false);
        for(String pending:new String[]{"queued","preparing","offered"})check(TransferPath.forAttachment("bluetooth",true,record(pending,true)).toString().equals("SELECTING"),"Negotiation should explain automatic route selection");
        check(TransferPath.forAttachment("bluetooth",true,plain)==TransferPath.UNKNOWN,"Unknown history guesses chat path");
        check(TransferPath.forAttachment("lan",true,null)==TransferPath.NONE,"Text row receives a file route");
        for(String invalid:new String[]{"wifi_direct","wifi","router",null}) {
            try {route(plain,invalid);throw new AssertionError("Invalid route accepted: "+invalid);}
            catch(InvocationTargetException rejected){check(rejected.getCause() instanceof IOException,"Route validation must reject invalid keys");}
        }
        for(int version:new int[]{1,2}) {
            AttachmentRecord legacy=version==1?new AttachmentRecord(new AttachmentInfo(UUID.randomUUID().toString(),"legacy.bin","application/octet-stream",8,"0".repeat(64),1),true,"transferring",4):plain;
            AttachmentRecord decoded=AttachmentRecord.decode(legacyEncode(legacy));
            check(decoded.equals(legacy)&&routeValue(decoded).isEmpty(),"Legacy record changes fields or invents a route");
            AttachmentRecord recovered=decoded.recovered();check(routeValue(recovered).isEmpty()&&!recovered.active(),"Legacy crash recovery invents a route");
            check(AttachmentRecord.decode(route(decoded,"lan").encode()).info.version==version,"New codec changes legacy attachment version");
        }
        System.out.println("Transfer path: "+checks+" checks passed (durable per-file routes, terminal history, legacy recovery)");
    }
    private static AttachmentRecord record(String state,boolean outgoing)throws Exception {
        AttachmentInfo info=AttachmentInfo.v2(UUID.randomUUID().toString(),"sample.bin","application/octet-stream",4096,"0".repeat(64),0);
        return new AttachmentRecord(info,outgoing,state,state.equals("received")||state.equals("delivered")?4096:512);
    }
    private static AttachmentRecord route(AttachmentRecord record,String route)throws Exception{return (AttachmentRecord)decorate.invoke(record,route);}
    private static String routeValue(AttachmentRecord record)throws Exception{return (String)AttachmentRecord.class.getField("route").get(record);}
    private static String legacyEncode(AttachmentRecord record)throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
        if(record.info.version==2){out.writeInt(2);record.info.writeV2(out);}else Protocol.write(bytes,record.info.offer());
        out.writeBoolean(record.outgoing);out.writeUTF(record.state);out.writeLong(record.transferred);
        return (record.info.version==2?"v2:":"")+Base64.getEncoder().encodeToString(bytes.toByteArray());
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
}
