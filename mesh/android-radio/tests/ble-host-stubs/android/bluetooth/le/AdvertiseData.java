package android.bluetooth.le;
public final class AdvertiseData {

    public static final class Builder {
        public Builder addServiceUuid(android.os.ParcelUuid uuid) { return this; }
        public Builder addServiceData(android.os.ParcelUuid uuid, byte[] data) { return this; }
        public AdvertiseData build() { return new AdvertiseData(); }
    }
}
