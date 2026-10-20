package dev.ghost.nearbyim.core;

import java.util.List;
import java.util.concurrent.TimeUnit;

/** Dormant outgoing history must not suppress an independent incoming P2P negotiation. */
public final class PausedQueueDirectGroupTests {
    public static void main(String[] args)throws Exception {
        for(String parent:List.of("bluetooth","lan"))
            try(var h=new AutomaticFileHandoverTests.Harness(parent,0,false)) {
                h.routes.a.platform.groups=h.routes.b.platform.groups=true;
                h.routes.a.busySupplier=h.files[0]::routeBusy;
                h.routes.b.busySupplier=h.files[1]::routeBusy;
                h.files[1].pauseBatch().get(3,TimeUnit.SECONDS);
                h.files[1].enqueue(List.of(AttachmentBatchQueue.selectFile(h.source))).get(3,TimeUnit.SECONDS);
                String paused=h.files[1].batchItems().get(0).info().id;
                check(!h.files[1].activity().active,"Fixture has an active engine instead of paused history");
                check(h.routes.a.files.ensureReady().get(5,TimeUnit.SECONDS)==AutomaticFileRoutes.Route.WIFI_DIRECT,
                        "Paused outgoing history blocked a capable peer's P2P route");
                check(h.routes.a.platform.groupCreates==1&&h.routes.b.platform.groupJoins==1
                                &&h.routes.a.platform.legacyOpens+h.routes.b.platform.legacyOpens==0,
                        "Paused outgoing history silently diverted known-group negotiation to DNS-SD over "+parent);
                h.transfer();
                check(h.files[1].batchItems().stream().anyMatch(item->item.info().id.equals(paused)&&item.state().equals("paused")),
                        "P2P admission resumed a manually paused outgoing task");
            }
        System.out.println("Paused queue P2P admission: Bluetooth/LAN known-group negotiation, real DATA and manual pause preserved");
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
