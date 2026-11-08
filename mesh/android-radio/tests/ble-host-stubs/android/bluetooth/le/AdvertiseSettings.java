package android.bluetooth.le;
public final class AdvertiseSettings {
    public static final int ADVERTISE_MODE_LOW_LATENCY = 2;
    public static final class Builder {
        public Builder setConnectable(boolean value) { return this; }
        public Builder setAdvertiseMode(int mode) { return this; }
        public Builder setTimeout(int timeout) { return this; }
        public AdvertiseSettings build() { return new AdvertiseSettings(); }
    }
}
