package android.bluetooth.le;

public abstract class AdvertiseCallback {
    public void onStartSuccess(AdvertiseSettings settings) { }
    public void onStartFailure(int error) { }
}
