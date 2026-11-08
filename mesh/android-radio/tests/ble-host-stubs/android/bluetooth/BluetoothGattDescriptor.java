package android.bluetooth;
import java.util.UUID;
public final class BluetoothGattDescriptor {
    public static final int PERMISSION_READ = 1, PERMISSION_WRITE = 16;
    public static final byte[] ENABLE_INDICATION_VALUE = {2,0}, DISABLE_NOTIFICATION_VALUE = {0,0};
    private final UUID uuid;
    BluetoothGattCharacteristic characteristic;
    public BluetoothGattDescriptor(UUID uuid, int permissions) { this.uuid = uuid; }
    public UUID getUuid() { return uuid; }
    public BluetoothGattCharacteristic getCharacteristic() { return characteristic; }
    public boolean setValue(byte[] value) { return true; }
}
