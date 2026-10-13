package dev.ghost.nearbyim;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Camera hardware is the only fake boundary; the focus controller runs unchanged. */
public final class QrCameraFocusTests {
    public static void main(String[] args) {
        continuousPictureIsPreferredForQr();
        continuousVideoIsUsedWhenPictureIsUnavailable();
        rejectedContinuousFocusFallsBackToAuto();
        silentlyRejectedFocusFallsBackToAuto();
        unsupportedOrUnreadableFocusKeepsScannerUsable();
        autoOnlyCameraRefocusesWithoutOverlappingRequests();
        missingHardwareCallbackRecoversAndIgnoresLateCompletion();
        stopRemovesRetriesAndRejectsOldCallbacks();
        reopeningCameraDoesNotReceiveOldFocusWork();
        rejectedAutofocusRetriesWithoutStoppingPreview();
        rejectedCancellationDoesNotPreventScannerCleanup();
        tapRefocusesAutoWithoutDuplicatingPeriodicWork();
        continuousCameraGetsBoundedAutomaticRecovery();
        tapActuallyFocusesContinuousCamera();
        tapTargetsTheSelectedSensorAreaBeforeAutofocus();
        tapSupersedesAutomaticFocusAndRejectsItsLateCallback();
        hardwareResultControlsVisibleFocusFeedback();
        timeoutAndCloseRejectLateVisibleFeedback();
        rejectedFocusAreaKeepsAutofocusUsable();
        continuousVideoUsesAutoToPerformARealFocusSweep();
        continuousOnlyCameraResumesAfterAutofocus();
        System.out.println("QR camera focus checks passed");
    }

    private static void continuousPictureIsPreferredForQr() {
        FakeDevice camera = new FakeDevice("auto", "continuous-video", "continuous-picture");
        QrCameraFocus focus = new QrCameraFocus(camera, new Clock());
        focus.configure();
        focus.start();
        equal("continuous-picture", camera.mode, "QR preview must enable continuous picture focus");
        equal(0, camera.requests, "Continuous focus must not be interrupted by AUTO requests");
    }

    private static void equal(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual)) throw new AssertionError(message + ": expected " + expected + ", got " + actual);
    }

    private static void continuousVideoIsUsedWhenPictureIsUnavailable() {
        FakeDevice camera = new FakeDevice("auto", "continuous-video");
        QrCameraFocus focus = new QrCameraFocus(camera, new Clock());
        configureWithoutStoppingPreview(focus);
        equal("continuous-video", camera.mode, "Use the remaining continuous focus mode");
    }

    private static void rejectedContinuousFocusFallsBackToAuto() {
        FakeDevice camera = new FakeDevice("continuous-picture", "continuous-video", "auto");
        camera.rejected.addAll(Arrays.asList("continuous-picture", "continuous-video"));
        QrCameraFocus focus = new QrCameraFocus(camera, new Clock());
        configureWithoutStoppingPreview(focus);
        equal("auto", camera.mode, "Vendor parameter rejection must fall back to AUTO");
    }

    private static void silentlyRejectedFocusFallsBackToAuto() {
        FakeDevice camera = new FakeDevice("continuous-picture", "continuous-video", "auto");
        camera.ignored.addAll(Arrays.asList("continuous-picture", "continuous-video"));
        QrCameraFocus focus = new QrCameraFocus(camera, new Clock());
        configureWithoutStoppingPreview(focus);
        equal("auto", camera.mode, "Read back accepted focus mode before deciding continuous focus is active");
    }

    private static void unsupportedOrUnreadableFocusKeepsScannerUsable() {
        FakeDevice fixed = new FakeDevice("fixed");
        QrCameraFocus focus = new QrCameraFocus(fixed, new Clock());
        configureWithoutStoppingPreview(focus);
        focus.start();
        focus.focusNow();
        equal("fixed", fixed.mode, "A fixed-focus camera should retain its working parameters");
        equal(0, fixed.requests, "Do not autofocus unsupported hardware");
        FakeDevice unreadable = new FakeDevice();
        unreadable.unreadable = true;
        configureWithoutStoppingPreview(new QrCameraFocus(unreadable, new Clock()));
    }

    private static void configureWithoutStoppingPreview(QrCameraFocus focus) {
        try { focus.configure(); }
        catch (RuntimeException failure) { throw new AssertionError("Optional focus configuration must not stop the QR preview", failure); }
    }

    private static void autoOnlyCameraRefocusesWithoutOverlappingRequests() {
        FakeDevice camera = new FakeDevice("auto");
        Clock clock = new Clock();
        QrCameraFocus focus = new QrCameraFocus(camera, clock);
        focus.configure();
        focus.start();
        focus.start();
        equal(1, camera.requests, "AUTO fallback must focus as preview starts");
        clock.advance(1000);
        equal(1, camera.requests, "Do not overlap autofocus while hardware is busy");
        camera.complete(0);
        clock.advance(1499);
        equal(1, camera.requests, "Avoid a tight autofocus loop");
        clock.advance(1);
        equal(2, camera.requests, "AUTO-only camera must refocus as QR distance changes");
        camera.complete(1);
        clock.advance(1500);
        equal(3, camera.requests, "Keep refocusing until scanning stops");
        focus.stop();
    }

    private static void missingHardwareCallbackRecoversAndIgnoresLateCompletion() {
        FakeDevice camera = new FakeDevice("auto");
        Clock clock = new Clock();
        QrCameraFocus focus = new QrCameraFocus(camera, clock);
        focus.configure();
        focus.start();
        clock.advance(2999);
        equal(1, camera.requests, "Allow bounded time for a slow camera to focus");
        clock.advance(1);
        equal(null, camera.active, "A lost autofocus callback must cancel the stuck request");
        clock.advance(1499);
        equal(1, camera.requests, "Wait between timeout recovery attempts");
        clock.advance(1);
        equal(2, camera.requests, "Missing callback must not stall focus for the rest of the scan");
        camera.complete(0);
        clock.advance(500);
        equal(2, camera.requests, "Late completion must not finish the newer request");
        camera.complete(1);
        clock.advance(1500);
        equal(3, camera.requests, "A valid completion must resume normal retries");
        equal(1, clock.pending.size(), "Only one bounded focus deadline should remain");
        focus.stop();
    }

    private static void stopRemovesRetriesAndRejectsOldCallbacks() {
        FakeDevice camera = new FakeDevice("auto");
        Clock clock = new Clock();
        QrCameraFocus focus = new QrCameraFocus(camera, clock);
        focus.configure();
        focus.start();
        focus.stop();
        focus.stop();
        equal(null, camera.active, "Close must cancel active hardware focus before camera release");
        equal(0, clock.pending.size(), "Close must remove the active focus deadline");
        camera.complete(0);
        clock.advance(20000);
        equal(1, camera.requests, "Callbacks after pause or destroy must not restart focus");
        focus.start();
        equal(2, camera.requests, "A resumed preview must focus again");
        camera.complete(0);
        camera.complete(1);
        focus.stop();
        equal(0, clock.pending.size(), "Close must remove a scheduled periodic retry");
        clock.advance(20000);
        equal(2, camera.requests, "Paused preview must stay idle");
    }

    private static void reopeningCameraDoesNotReceiveOldFocusWork() {
        Clock clock = new Clock();
        FakeDevice oldCamera = new FakeDevice("auto");
        QrCameraFocus oldFocus = new QrCameraFocus(oldCamera, clock);
        oldFocus.configure();
        oldFocus.start();
        oldFocus.stop();
        FakeDevice reopened = new FakeDevice("auto");
        QrCameraFocus newFocus = new QrCameraFocus(reopened, clock);
        newFocus.configure();
        newFocus.start();
        oldCamera.complete(0);
        clock.advance(1000);
        equal(1, oldCamera.requests, "Released camera must receive no more focus requests");
        equal(1, reopened.requests, "Old callback must not interrupt reopened camera focus");
        reopened.complete(0);
        clock.advance(1500);
        equal(2, reopened.requests, "Reopened camera keeps its own focus lifecycle");
        newFocus.stop();
        equal(0, clock.pending.size(), "All focus work must be gone after both previews close");
    }

    private static void rejectedAutofocusRetriesWithoutStoppingPreview() {
        FakeDevice camera = new FakeDevice("auto");
        camera.rejectRequests = true;
        Clock clock = new Clock();
        QrCameraFocus focus = new QrCameraFocus(camera, clock);
        focus.configure();
        try { focus.start(); }
        catch (RuntimeException failure) { throw new AssertionError("Vendor autofocus failure must keep preview usable", failure); }
        clock.advance(1499);
        equal(1, camera.requests, "A rejected focus request must not create a tight retry loop");
        clock.advance(1);
        equal(2, camera.requests, "A rejected request should be retried at the normal interval");
        camera.rejectRequests = false;
        clock.advance(1500);
        equal(3, camera.requests, "A transient vendor failure must recover");
        camera.complete(0);
        focus.stop();
        equal(0, clock.pending.size(), "Rejected autofocus must not leak retry work");
    }

    private static void rejectedCancellationDoesNotPreventScannerCleanup() {
        FakeDevice camera = new FakeDevice("auto");
        Clock clock = new Clock();
        QrCameraFocus focus = new QrCameraFocus(camera, clock);
        focus.configure();
        focus.start();
        camera.rejectCancellation = true;
        try { clock.advance(3000); focus.stop(); }
        catch (RuntimeException failure) { throw new AssertionError("Vendor cancel failure must not prevent QR camera cleanup", failure); }
        equal(0, clock.pending.size(), "Cleanup must remove work even when hardware rejects cancellation");
        camera.complete(0);
        clock.advance(10000);
        equal(1, camera.requests, "Rejected cancellation must not restart a closed scanner");
    }

    private static void tapRefocusesAutoWithoutDuplicatingPeriodicWork() {
        FakeDevice camera = new FakeDevice("auto");
        Clock clock = new Clock();
        QrCameraFocus focus = new QrCameraFocus(camera, clock);
        focus.configure();
        focus.start();
        camera.complete(0);
        clock.advance(200);
        focus.focusNow();
        equal(2, camera.requests, "Tap should refocus immediately rather than wait for periodic retry");
        camera.complete(1);
        clock.advance(1300);
        equal(2, camera.requests, "Tap must replace the old periodic retry");
        clock.advance(200);
        equal(3, camera.requests, "Tap must preserve periodic focusing afterward");
        focus.stop();
        focus.focusNow();
        equal(3, camera.requests, "Tap after closing must not use a released camera");
    }

    private static void tapActuallyFocusesContinuousCamera() {
        FakeDevice camera = new FakeDevice("continuous-picture", "auto");
        Clock clock = new Clock();
        QrCameraFocus focus = new QrCameraFocus(camera, clock);
        focus.configure();
        focus.start();
        focus.focusNow();
        equal(1, camera.requests, "Tap must perform an actual hardware autofocus request");
        camera.complete(0);
        clock.advance(1000);
        equal("continuous-picture", camera.mode, "Continuous focus must resume after the one-shot focus hold");
        focus.stop();
    }

    private static void continuousCameraGetsBoundedAutomaticRecovery() {
        FakeDevice camera = new FakeDevice("continuous-picture", "auto");
        Clock clock = new Clock();
        QrCameraFocus focus = new QrCameraFocus(camera, clock);
        focus.configure();
        focus.start();
        clock.advance(2199);
        equal(0, camera.requests, "Give continuous focus time to settle before recovery");
        clock.advance(1);
        equal(1, camera.requests, "A static QR must receive a bounded hardware focus recovery");
        clock.advance(2999);
        equal(1, camera.requests, "Recovery must not overlap a slow hardware focus request");
        focus.stop();
        equal(0, clock.pending.size(), "Close removes continuous-focus recovery work");
    }

    private static void tapTargetsTheSelectedSensorAreaBeforeAutofocus() {
        FakeDevice camera = new FakeDevice("continuous-picture", "auto");
        Clock clock = new Clock();
        QrCameraFocus focus = new QrCameraFocus(camera, clock);
        focus.configure(); focus.start();
        QrFocusPoint.Area target = QrFocusPoint.at(750, 500, 1000, 1000, 800, 800, 0, false);
        focus.focusNow(target);
        equal(target, camera.areaAtRequest, "Set the tapped focus/metering region before hardware autofocus");
        camera.complete(0); clock.advance(1000);
        equal(null, camera.area, "Resume normal continuous focusing without an old tap region");
        focus.stop();
    }

    private static void tapSupersedesAutomaticFocusAndRejectsItsLateCallback() {
        FakeDevice camera = new FakeDevice("auto");
        Clock clock = new Clock();
        QrCameraFocus focus = new QrCameraFocus(camera, clock);
        focus.configure(); focus.start();
        focus.focusNow();
        equal(2, camera.requests, "Manual focus must cancel and replace a slow automatic request");
        camera.complete(0); clock.advance(1000);
        equal(2, camera.requests, "A canceled automatic callback must not complete the manual request");
        camera.complete(1); clock.advance(1500);
        equal(3, camera.requests, "Manual focus must leave one periodic retry");
        focus.stop();
    }

    private static void hardwareResultControlsVisibleFocusFeedback() {
        FakeDevice camera = new FakeDevice("auto");
        List<QrCameraFocus.State> states = new ArrayList<>();
        QrCameraFocus focus = new QrCameraFocus(camera, new Clock(), (state, area) -> states.add(state));
        focus.configure(); focus.start();
        equal(QrCameraFocus.State.FOCUSING, last(states), "Display focusing when hardware begins");
        camera.complete(0, false);
        equal(QrCameraFocus.State.FAILED, last(states), "A hardware focus failure must not display success");
        focus.focusNow(); camera.complete(1, true);
        equal(QrCameraFocus.State.FOCUSED, last(states), "Display focused only after hardware reports success");
        focus.stop();
    }

    private static void timeoutAndCloseRejectLateVisibleFeedback() {
        FakeDevice camera = new FakeDevice("continuous-picture", "auto");
        Clock clock = new Clock(); List<QrCameraFocus.State> states = new ArrayList<>();
        QrCameraFocus focus = new QrCameraFocus(camera, clock, (state, area) -> states.add(state));
        focus.configure(); focus.start(); focus.focusNow();
        clock.advance(3000);
        equal(QrCameraFocus.State.FAILED, last(states), "A focus timeout must be visible");
        camera.complete(0, true);
        equal(QrCameraFocus.State.FAILED, last(states), "A timed-out callback cannot display focus success");
        focus.focusNow(); focus.stop(); int count = states.size();
        camera.complete(1, true); clock.advance(10000);
        equal(count, states.size(), "A closed camera cannot update focus feedback");
        equal(0, clock.pending.size(), "Close removes focus hold, timeout, and recovery work");
    }

    private static void rejectedFocusAreaKeepsAutofocusUsable() {
        FakeDevice camera = new FakeDevice("auto"); camera.rejectArea = true;
        QrCameraFocus focus = new QrCameraFocus(camera, new Clock());
        focus.configure(); focus.start(); camera.complete(0);
        focus.focusNow(QrFocusPoint.at(500, 500, 1000, 1000, 800, 800, 0, false));
        equal(2, camera.requests, "Optional focus-area rejection must not disable autofocus");
        focus.stop();
    }

    private static void continuousVideoUsesAutoToPerformARealFocusSweep() {
        FakeDevice camera = new FakeDevice("continuous-video", "auto");
        Clock clock = new Clock(); QrCameraFocus focus = new QrCameraFocus(camera, clock);
        focus.configure(); focus.start(); focus.focusNow();
        equal("auto", camera.modeAtRequest, "Continuous video autofocus can just lock; use AUTO for a full sweep");
        camera.complete(0); clock.advance(1000);
        equal("continuous-video", camera.mode, "Restore continuous video after a bounded focus hold");
        focus.stop();
    }

    private static void continuousOnlyCameraResumesAfterAutofocus() {
        FakeDevice camera = new FakeDevice("continuous-picture");
        Clock clock = new Clock(); QrCameraFocus focus = new QrCameraFocus(camera, clock);
        focus.configure(); focus.start(); focus.focusNow();
        equal(1, camera.requests, "Continuous picture supports one-shot autofocus without AUTO mode");
        camera.complete(0); clock.advance(1000);
        equal(null, camera.active, "Release one-shot lock to resume the continuous sweep");
        equal("continuous-picture", camera.mode, "Keep the supported continuous mode");
        focus.stop();
    }

    private static QrCameraFocus.State last(List<QrCameraFocus.State> states) {
        return states.isEmpty() ? null : states.get(states.size() - 1);
    }

    private static final class FakeDevice implements QrCameraFocus.Device {
        final List<String> modes;
        final List<String> rejected = new ArrayList<>();
        final List<String> ignored = new ArrayList<>();
        boolean unreadable;
        boolean rejectRequests;
        boolean rejectCancellation;
        boolean rejectArea;
        String mode = "fixed";
        int requests;
        int cancellations;
        QrFocusPoint.Area area, areaAtRequest;
        String modeAtRequest;
        final List<QrCameraFocus.Completion> callbacks = new ArrayList<>();
        QrCameraFocus.Completion active;
        FakeDevice(String... modes) { this.modes = Arrays.asList(modes); }
        public List<String> supportedModes() { if (unreadable) throw new RuntimeException("Vendor parameters unavailable"); return modes; }
        public String currentMode() { if (unreadable) throw new RuntimeException("Vendor parameters unavailable"); return mode; }
        public void setMode(String selected) {
            if (rejected.contains(selected)) throw new RuntimeException("Vendor rejected " + selected);
            if (!ignored.contains(selected)) mode = selected;
        }
        public void setFocusArea(QrFocusPoint.Area selected) {
            if (rejectArea) throw new RuntimeException("Vendor focus areas unavailable");
            area = selected;
        }
        public void autoFocus(QrCameraFocus.Completion completed) {
            requests++;
            areaAtRequest = area; modeAtRequest = mode;
            if (rejectRequests) throw new RuntimeException("Vendor autofocus failure");
            if (active != null) throw new AssertionError("Overlapping hardware autofocus requests");
            callbacks.add(completed);
            active = completed;
        }
        public void cancelAutoFocus() { cancellations++; if (rejectCancellation) throw new RuntimeException("Vendor cancellation failure"); active = null; }
        void complete(int request) { complete(request, true); }
        void complete(int request, boolean success) {
            QrCameraFocus.Completion callback = callbacks.get(request);
            if (active == callback) active = null;
            callback.completed(success);
        }
    }

    private static final class Clock implements QrCameraFocus.Scheduler {
        final List<TimedTask> pending = new ArrayList<>();
        long now;
        public void postDelayed(Runnable task, long delayMillis) { pending.add(new TimedTask(task, now + delayMillis)); }
        public void remove(Runnable task) { pending.removeIf(entry -> entry.task == task); }
        void advance(long millis) {
            long target = now + millis;
            int steps = 0;
            while (true) {
                TimedTask next = null;
                for (TimedTask candidate : pending) if (candidate.time <= target && (next == null || candidate.time < next.time)) next = candidate;
                if (next == null) break;
                if (++steps > 10000) throw new AssertionError("Focus retry loop is unbounded");
                pending.remove(next);
                now = next.time;
                next.task.run();
            }
            now = target;
        }
    }
    private static final class TimedTask {
        final Runnable task;
        final long time;
        TimedTask(Runnable task, long time) { this.task = task; this.time = time; }
    }
}
