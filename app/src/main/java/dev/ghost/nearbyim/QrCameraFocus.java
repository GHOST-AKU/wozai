package dev.ghost.nearbyim;

import java.util.List;

/** Focus lifecycle for one camera preview; all calls use the camera's UI thread. */
final class QrCameraFocus {
    private static final long RETRY_MILLIS = 1500;
    private static final long TIMEOUT_MILLIS = 3000;
    interface Device {
        List<String> supportedModes();
        String currentMode();
        void setMode(String mode);
        void autoFocus(Runnable completed);
        void cancelAutoFocus();
    }
    interface Scheduler {
        void postDelayed(Runnable task, long delayMillis);
        void remove(Runnable task);
    }
    private final Device device;
    private final Scheduler scheduler;
    private final Runnable nextFocus = this::requestFocus;
    private String mode;
    private boolean running;
    private Object attempt;
    private Runnable focusTimeout;
    QrCameraFocus(Device device, Scheduler scheduler) {
        this.device = device;
        this.scheduler = scheduler;
    }
    void configure() {
        mode = readMode();
        List<String> supported;
        try { supported = device.supportedModes(); }
        catch (RuntimeException unsupported) { return; }
        for (String candidate : new String[]{"continuous-picture", "continuous-video", "auto"}) {
            if (supported != null && supported.contains(candidate)) {
                try {
                    device.setMode(candidate);
                    mode = readMode();
                    if (candidate.equals(mode)) return;
                } catch (RuntimeException rejected) { }
            }
        }
        mode = readMode();
    }
    private String readMode() {
        try { return device.currentMode(); }
        catch (RuntimeException unsupported) { return null; }
    }
    void start() {
        if (running) return;
        running = true;
        requestFocus();
    }
    private void requestFocus() {
        if (!running || !"auto".equals(mode) || attempt != null) return;
        cancelFocus();
        Object token = new Object();
        attempt = token;
        focusTimeout = () -> timedOut(token);
        scheduler.postDelayed(focusTimeout, TIMEOUT_MILLIS);
        try { device.autoFocus(() -> completed(token)); }
        catch (RuntimeException rejected) { cancelFocus(); completed(token); }
    }
    private void completed(Object token) {
        if (!running || attempt != token) return;
        attempt = null;
        scheduler.remove(focusTimeout);
        focusTimeout = null;
        scheduler.postDelayed(nextFocus, RETRY_MILLIS);
    }
    private void timedOut(Object token) {
        if (!running || attempt != token) return;
        attempt = null;
        focusTimeout = null;
        cancelFocus();
        scheduler.postDelayed(nextFocus, RETRY_MILLIS);
    }
    void stop() {
        if (!running) return;
        running = false;
        attempt = null;
        scheduler.remove(nextFocus);
        if (focusTimeout != null) scheduler.remove(focusTimeout);
        focusTimeout = null;
        cancelFocus();
    }
    private void cancelFocus() {
        try { device.cancelAutoFocus(); }
        catch (RuntimeException unsupported) { }
    }
    void focusNow() {
        if (!running) return;
        if ("continuous-picture".equals(mode) || "continuous-video".equals(mode)) {
            // cancelAutoFocus resumes the continuous sweep without locking one-shot focus.
            cancelFocus();
        } else if ("auto".equals(mode) && attempt == null) {
            scheduler.remove(nextFocus);
            requestFocus();
        }
    }
}
