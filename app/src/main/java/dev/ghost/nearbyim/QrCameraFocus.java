package dev.ghost.nearbyim;

import java.util.List;

/** Focus lifecycle for one camera preview; all calls use the camera's UI thread. */
final class QrCameraFocus {
    private static final long RETRY_MILLIS = 1500;
    private static final long TIMEOUT_MILLIS = 3000;
    private static final long CONTINUOUS_RECOVERY_MILLIS = 2200;
    private static final long FOCUS_HOLD_MILLIS = 1000;
    enum State { IDLE, FOCUSING, FOCUSED, FAILED, UNAVAILABLE }
    interface Completion { void completed(boolean success); }
    interface Listener { void changed(State state, QrFocusPoint.Area area); }
    interface Device {
        List<String> supportedModes();
        String currentMode();
        void setMode(String mode);
        default void setFocusArea(QrFocusPoint.Area area) { }
        void autoFocus(Completion completed);
        void cancelAutoFocus();
    }
    interface Scheduler {
        void postDelayed(Runnable task, long delayMillis);
        void remove(Runnable task);
    }
    private final Device device;
    private final Scheduler scheduler;
    private final Listener listener;
    private final Runnable nextFocus = this::requestFocus;
    private final Runnable resumeContinuous = this::resumeContinuous;
    private String mode;
    private boolean supportsAuto;
    private boolean running;
    private Object attempt;
    private Runnable focusTimeout;
    private QrFocusPoint.Area focusArea;
    QrCameraFocus(Device device, Scheduler scheduler) {
        this(device, scheduler, (state, area) -> { });
    }
    QrCameraFocus(Device device, Scheduler scheduler, Listener listener) {
        this.device = device;
        this.scheduler = scheduler;
        this.listener = listener;
    }
    void configure() {
        mode = readMode();
        List<String> supported;
        try { supported = device.supportedModes(); }
        catch (RuntimeException unsupported) { return; }
        supportsAuto = supported != null && supported.contains("auto");
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
        listener.changed(isContinuous() || "auto".equals(mode) ? State.IDLE : State.UNAVAILABLE, null);
        if (isContinuous()) scheduler.postDelayed(nextFocus, CONTINUOUS_RECOVERY_MILLIS);
        else requestFocus();
    }
    private void requestFocus() {
        requestFocus(null);
    }
    private void requestFocus(QrFocusPoint.Area area) {
        if (!running || (!"auto".equals(mode) && !isContinuous()) || attempt != null) return;
        scheduler.remove(resumeContinuous);
        cancelFocus();
        if (isContinuous() && supportsAuto) {
            try { device.setMode("auto"); }
            catch (RuntimeException rejected) { }
        }
        focusArea = area;
        setFocusArea(area);
        Object token = new Object();
        attempt = token;
        focusTimeout = () -> timedOut(token);
        scheduler.postDelayed(focusTimeout, TIMEOUT_MILLIS);
        listener.changed(State.FOCUSING, focusArea);
        try { device.autoFocus(success -> completed(token, success)); }
        catch (RuntimeException rejected) { cancelFocus(); completed(token, false); }
    }
    private void completed(Object token, boolean success) {
        if (!running || attempt != token) return;
        attempt = null;
        scheduler.remove(focusTimeout);
        focusTimeout = null;
        listener.changed(success ? State.FOCUSED : State.FAILED, focusArea);
        if (isContinuous() && success) scheduler.postDelayed(resumeContinuous, FOCUS_HOLD_MILLIS);
        else if (isContinuous()) resumeContinuous();
        else scheduler.postDelayed(nextFocus, RETRY_MILLIS);
    }
    private void timedOut(Object token) {
        if (!running || attempt != token) return;
        attempt = null;
        focusTimeout = null;
        cancelFocus();
        listener.changed(State.FAILED, focusArea);
        if (isContinuous()) resumeContinuous();
        else scheduler.postDelayed(nextFocus, RETRY_MILLIS);
    }
    private boolean isContinuous() {
        return "continuous-picture".equals(mode) || "continuous-video".equals(mode);
    }
    private void resumeContinuous() {
        if (!running) return;
        cancelFocus();
        try { device.setMode(mode); }
        catch (RuntimeException rejected) { }
        setFocusArea(null);
        scheduler.postDelayed(nextFocus, CONTINUOUS_RECOVERY_MILLIS);
    }
    void stop() {
        if (!running) return;
        running = false;
        attempt = null;
        scheduler.remove(nextFocus);
        scheduler.remove(resumeContinuous);
        if (focusTimeout != null) scheduler.remove(focusTimeout);
        focusTimeout = null;
        cancelFocus();
        if (isContinuous()) {
            try { device.setMode(mode); }
            catch (RuntimeException rejected) { }
        }
        setFocusArea(null);
        listener.changed(State.IDLE, null);
    }
    private void cancelFocus() {
        try { device.cancelAutoFocus(); }
        catch (RuntimeException unsupported) { }
    }
    private void setFocusArea(QrFocusPoint.Area area) {
        try { device.setFocusArea(area); }
        catch (RuntimeException rejected) { }
    }
    void focusNow() { focusNow(null); }
    void focusNow(QrFocusPoint.Area area) {
        if (!running) return;
        if (isContinuous() || "auto".equals(mode)) {
            scheduler.remove(nextFocus);
            attempt = null;
            if (focusTimeout != null) scheduler.remove(focusTimeout);
            focusTimeout = null;
            requestFocus(area);
        }
    }
}
