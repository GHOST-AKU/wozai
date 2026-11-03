package android.bluetooth.le;
import java.util.List;
public final class BluetoothLeScanner {
    public int starts, stops;
    public void startScan(List<ScanFilter> filters, ScanSettings settings, ScanCallback callback) { starts++; }
    public void stopScan(ScanCallback callback) { stops++; }
}
