package dev.ghost.nearbyim.transport;

import android.app.Instrumentation;
import android.net.wifi.p2p.WifiP2pConfig;
import dev.ghost.nearbyim.core.DirectGroupCredentials;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/** Real public framework builders and waiting-state lifecycle; no claim of radio connectivity. */
public final class AndroidKnownGroupChecks {
    public static int run(Instrumentation instrumentation)throws Exception {
        DirectGroupCredentials credentials=DirectGroupCredentials.random();int checks=0;
        WifiP2pConfig owner=WifiDirectTransport.groupConfig(credentials,true);
        check(owner.getNetworkName().equals(credentials.ssid)&&owner.getPassphrase().equals(credentials.password),"Public builder retains per-attempt group parameters");checks++;
        check(owner.getGroupOwnerBand()==WifiP2pConfig.GROUP_OWNER_BAND_5GHZ,"GO explicitly requests 5GHz");checks++;
        WifiP2pConfig automatic=WifiDirectTransport.groupConfig(credentials,false);
        check(automatic.getGroupOwnerBand()==WifiP2pConfig.GROUP_OWNER_BAND_AUTO,"Confirmed-failure fallback can use AUTO");checks++;
        WifiP2pConfig client=WifiDirectTransport.groupConfig(credentials.withFrequency(5180),false);
        check(client.getGroupOwnerBand()==5180&&client.getNetworkName().equals(credentials.ssid),"Joiner scans the actual supplied group frequency");checks++;
        AtomicReference<AndroidFilePlatform> platform=new AtomicReference<>();String nonce=UUID.randomUUID().toString();
        instrumentation.runOnMainSync(()->{
            AndroidFilePlatform value=new AndroidFilePlatform(instrumentation.getTargetContext(),UUID.randomUUID().toString(),"known-group-check",UUID.randomUUID().toString());platform.set(value);value.prepareDirectGroup(nonce,false);
        });
        try{
            String report=platform.get().diagnostics();
            check(report.contains("wifi_direct_attempt="+nonce+"\n")&&report.contains("wifi_direct_phase=waiting_group_parameters\n"),"Waiting joiner has this attempt's diagnostics before credentials arrive");checks++;
            check(report.contains("wifi_direct_method=known_group\n")&&!report.contains(credentials.password)&&!report.contains(credentials.ssid),"Diagnostics identify the method without exposing credentials");checks++;
            instrumentation.runOnMainSync(()->platform.get().stopDirect());
            check(platform.get().diagnostics().contains(":stop"),"Canceled parameter wait freezes its timeline");checks++;
        }finally{instrumentation.runOnMainSync(()->platform.get().close());}
        return checks;
    }
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
