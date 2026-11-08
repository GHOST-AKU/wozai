package android.bluetooth.le;

import java.util.List;

public abstract class ScanCallback {
    public void onScanResult(int type, ScanResult result) { }
    public void onBatchScanResults(List<ScanResult> results) { }
    public void onScanFailed(int error) { }
}
