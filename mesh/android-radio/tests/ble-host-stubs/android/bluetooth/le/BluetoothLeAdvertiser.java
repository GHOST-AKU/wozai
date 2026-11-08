package android.bluetooth.le;
public final class BluetoothLeAdvertiser {
    public int starts, stops;
    public AdvertiseCallback callback;
    public void startAdvertising(AdvertiseSettings settings, AdvertiseData primary, AdvertiseData response, AdvertiseCallback callback) {
        starts++; this.callback = callback;
    }
    public void stopAdvertising(AdvertiseCallback callback) { stops++; }
}
