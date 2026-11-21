package dev.ghost.nearbyim;

import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import dev.ghost.nearbyim.transport.Peer;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 34, shadows = {
        PrivateRequestsIntegrationTest.TestRoot.class, PrivateRequestsIntegrationTest.TestNoiseKey.class
})
public class PrivateReceptionLifecycleTest {
    @Test public void optedInReceptionStartsOnOpenAndTheNotificationStopDisablesRestore() {
        Application app = RuntimeEnvironment.getApplication();
        ServiceController<ChatService> service = Robolectric.buildService(ChatService.class).create();
        Context context = service.get();
        Intent binding = new Intent(app, ChatService.class);
        shadowOf(app).setComponentNameAndServiceForBindServiceForIntent(binding,
                new ComponentName(app, ChatService.class), service.get().onBind(binding));
        ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class);
        try {
            assertFalse(PrivateReception.enabled(context, Peer.LAN));
            activity.setup();
            shadowOf(Looper.getMainLooper()).idle();
            assertNull(nextReceptionStart(app));
            assertFalse(service.get().controller.lanRunning);
            PrivateReception.setEnabled(context, Peer.LAN, true);

            activity.pause().resume();
            shadowOf(Looper.getMainLooper()).idle();
            Intent start = nextReceptionStart(app);
            assertNotNull(start);
            assertTrue(service.get().controller.lanRunning);
            service.get().onStartCommand(start, 0, 1);
            assertNotNull(shadowOf(service.get()).getLastForegroundNotification());

            service.get().onStartCommand(new Intent(app, ChatService.class).setAction(ChatService.STOP), 0, 2);
            assertFalse(PrivateReception.enabled(context, Peer.LAN));
            assertFalse(service.get().controller.lanRunning);
            activity.pause().resume();
            shadowOf(Looper.getMainLooper()).idle();
            assertNull(nextReceptionStart(app));
        } finally {
            activity.pause().stop().destroy();
            service.destroy();
        }
    }

    private static Intent nextReceptionStart(Application app) {
        // Robolectric also records the actionless intent from BIND_AUTO_CREATE.
        Intent next;
        while ((next = shadowOf(app).getNextStartedService()) != null) {
            if (ChatService.START.equals(next.getAction())) return next;
        }
        return null;
    }
}
