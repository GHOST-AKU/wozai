package android.bluetooth;
import java.util.*;
public final class BluetoothGattCharacteristic {
    public static final int PROPERTY_WRITE = 8, PROPERTY_INDICATE = 32, PERMISSION_WRITE = 16, WRITE_TYPE_DEFAULT = 2;
    private final UUID uuid;
    private final int properties;
    private final Map<UUID,BluetoothGattDescriptor> descriptors = new HashMap<>();
    private byte[] value;
    public BluetoothGattCharacteristic(UUID uuid, int properties, int permissions) { this.uuid = uuid; this.properties = properties; }
    public int getProperties() { return properties; }
    public UUID getUuid() { return uuid; }
    public boolean addDescriptor(BluetoothGattDescriptor descriptor) { descriptors.put(descriptor.getUuid(),descriptor); descriptor.characteristic = this; return true; }
    public BluetoothGattDescriptor getDescriptor(UUID uuid) { return descriptors.get(uuid); }
    public boolean setValue(byte[] value) { this.value = value; return true; }
    public byte[] getValue() { return value; }
    public void setWriteType(int type) { }
}
