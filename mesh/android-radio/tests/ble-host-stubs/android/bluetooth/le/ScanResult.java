package android.bluetooth.le;
import android.bluetooth.BluetoothDevice;
public final class ScanResult {
    private final BluetoothDevice device;private final ScanRecord record;
    public ScanResult(BluetoothDevice device,ScanRecord record){this.device=device;this.record=record;}
    public ScanRecord getScanRecord(){return record;}
    public BluetoothDevice getDevice(){return device;}
}
