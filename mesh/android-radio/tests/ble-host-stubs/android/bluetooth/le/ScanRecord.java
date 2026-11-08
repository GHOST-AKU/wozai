package android.bluetooth.le;
import android.os.ParcelUuid;
public final class ScanRecord {
    private final byte[] label;
    public ScanRecord(byte[] label){this.label=label.clone();}
    public byte[] getServiceData(ParcelUuid service){return label.clone();}
}
