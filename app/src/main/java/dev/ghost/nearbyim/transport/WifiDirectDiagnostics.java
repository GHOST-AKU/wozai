package dev.ghost.nearbyim.transport;

import java.util.*;
import java.util.function.LongSupplier;

/** Attempt-local, bounded monotonic events. Enum labels prevent addresses/credentials entering logs. */
final class WifiDirectDiagnostics {
    enum Event {
        ATTEMPT_START,CHANNEL_READY,ADVERTISEMENT_READY,ADVERTISEMENT_FAILED,WARM_ADVERTISEMENT,
        QUERY_CLEAR_SUBMIT,QUERY_CLEAR_ACK,QUERY_CLEAR_FAILED,QUERY_ADD_SUBMIT,QUERY_ADD_ACK,QUERY_ADD_FAILED,
        SCAN_SUBMIT,SCAN_ACK,SCAN_FAILED,DISCOVERY_STARTED,DISCOVERY_STOPPED,SERVICE_RESPONSE,TXT_RESPONSE,
        TARGET_DISCOVERED,CONNECT_SUBMIT,CONNECT_ACK,CONNECT_FAILED,GROUP_OBSERVED,SOCKET_OPENED,
        PREPARATION_TIMEOUT,DISCOVERY_TIMEOUT,OPEN_TIMEOUT,LOCAL_FAILURE,PEER_FALLBACK,STOP,
        CREATE_GROUP_SUBMIT,CREATE_GROUP_ACK,CREATE_GROUP_FAILED,JOIN_GROUP_SUBMIT,JOIN_GROUP_ACK,JOIN_GROUP_FAILED,
        GROUP_AUTO_RETRY,GROUP_PARAMETERS_SENT,GROUP_PARAMETERS_RECEIVED,
        GROUP_WAIT_INFO,GROUP_WAIT_ROLE,GROUP_WAIT_ADDRESS,GROUP_WAIT_INTERFACE,GROUP_READINESS_TIMEOUT
    }
    private final LongSupplier clock;
    private final ArrayList<String> events=new ArrayList<>(48);
    private final Set<Event> first=EnumSet.noneOf(Event.class);
    private String attempt="",role="unknown";
    private long began,ended;private int dropped;private boolean running;
    WifiDirectDiagnostics(LongSupplier clock){this.clock=clock;}
    synchronized void begin(String nonce,boolean coordinator){
        // This is a random FILE attempt nonce, never a device ID, root identity or network address.
        attempt=UUID.fromString(nonce).toString();role=coordinator?"coordinator":"responder";
        events.clear();first.clear();dropped=0;began=clock.getAsLong();ended=began;running=true;record(Event.ATTEMPT_START);
    }
    synchronized void record(Event event){record(event,null);}
    synchronized void record(Event event,int code){record(event,Integer.valueOf(code));}
    synchronized void recordOnce(Event event){if(running&&first.add(event))record(event);}
    private void record(Event event,Integer code){
        if(!running)return;
        // Keep startup evidence as well as recent events when a ROM floods broadcasts.
        if(events.size()==48){events.remove(16);dropped++;}
        events.add(Math.max(0,clock.getAsLong()-began)+":"+event.name().toLowerCase(Locale.ROOT)+(code==null?"":":"+code));
    }
    synchronized void stop(){if(!running)return;record(Event.STOP);ended=clock.getAsLong();running=false;}
    synchronized int eventCount(){return events.size();}
    synchronized String snapshot(){
        return "wifi_direct_diagnostics_schema=2\nwifi_direct_attempt="+attempt+"\nwifi_direct_role="+role+"\nwifi_direct_attempt_elapsed_ms="+(attempt.isEmpty()?0:Math.max(0,(running?clock.getAsLong():ended)-began))+"\nwifi_direct_trace_dropped="+dropped+"\nwifi_direct_trace="+String.join(";",events)+"\n";
    }
}
