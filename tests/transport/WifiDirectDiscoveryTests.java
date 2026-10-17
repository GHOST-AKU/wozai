package dev.ghost.nearbyim.transport;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** Deterministic callback time, not a simulation of Android radio discovery. */
public final class WifiDirectDiscoveryTests {
    private static int checks;
    public static void main(String[] args){
        var budget=new WifiDirectPolicy.DiscoveryDeadline(5000,5000);
        check(budget.prepare(1000),"Advertisement starts bounded preparation");
        check(!budget.prepare(4000),"Duplicate advertising cannot reset preparation");
        check(budget.scan(5000),"A delayed native scan ACK starts the actual discovery budget");
        check(budget.failure(6000).isEmpty(),"Four seconds of preparation cannot steal discovery time");
        check(!budget.scan(9000),"Duplicate scan ACK cannot extend discovery");
        check(budget.failure(10000).equals("discovery_timeout"),"Target-free discovery expires after five actual scan seconds");
        budget.stop();check(!budget.scan(12000)&&budget.failure(20000).isEmpty(),"Late ACK cannot revive a stopped attempt");
        budget.reset();check(budget.prepare(20000),"Retry has a new bounded preparation budget");
        check(budget.failure(25000).equals("discovery_prepare_timeout"),"Missing scan ACK is preparation failure, not scanned-without-target");
        check(!budget.scan(25000),"A late scan ACK cannot escape an already expired preparation budget");
        budget.reset();budget.prepare(30000);budget.found();
        check(!budget.scan(31000)&&budget.failure(40000).isEmpty(),"A discovered target keeps the separate group connection budget");
        AtomicLong clock=new AtomicLong(1000);var trace=new WifiDirectDiagnostics(clock::get);
        String token=UUID.randomUUID().toString();trace.begin(token,true);
        clock.set(2000);trace.record(WifiDirectDiagnostics.Event.QUERY_ADD_ACK,0);
        clock.set(4000);trace.record(WifiDirectDiagnostics.Event.SCAN_ACK,0);
        clock.set(9000);trace.record(WifiDirectDiagnostics.Event.DISCOVERY_TIMEOUT);trace.stop();
        String report=trace.snapshot();
        check(report.contains("wifi_direct_role=coordinator\n")&&report.contains("wifi_direct_attempt="+token+"\n"),"Two endpoints can correlate a FILE attempt without root identity or address");
        check(report.contains("1000:query_add_ack:0")&&report.contains("3000:scan_ack:0")&&report.contains("8000:discovery_timeout"),"Preparation and scan time remain distinguishable");
        clock.set(12000);trace.record(WifiDirectDiagnostics.Event.SCAN_ACK,0);
        check(report.equals(trace.snapshot()),"Late native callbacks and later copying cannot overwrite the stopped timeline");
        trace.begin(UUID.randomUUID().toString(),false);
        check(!trace.snapshot().contains("query_add_ack")&&trace.snapshot().contains("wifi_direct_role=responder\n"),"Retry starts a clean diagnostic timeline");
        for(int i=0;i<200;i++)trace.record(WifiDirectDiagnostics.Event.DISCOVERY_STARTED);
        check(trace.eventCount()<=48&&trace.snapshot().length()<5000,"Broadcast storms have bounded diagnostic memory and output");
        check(trace.snapshot().contains("0:attempt_start"),"A broadcast storm cannot evict the original attempt start");
        System.out.println("Wi-Fi Direct discovery timing/trace: "+checks+" checks passed");
    }
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);checks++;}
}
