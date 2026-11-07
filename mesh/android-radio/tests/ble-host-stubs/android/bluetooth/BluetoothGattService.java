package android.bluetooth;
import java.util.*;
public final class BluetoothGattService {
    public static final int SERVICE_TYPE_PRIMARY = 0;
    private final UUID uuid;
    private final Map<UUID,BluetoothGattCharacteristic> characteristics = new HashMap<>();
    public BluetoothGattService(UUID uuid, int type) { this.uuid = uuid; }
    public UUID getUuid() { return uuid; }
    public boolean addCharacteristic(BluetoothGattCharacteristic characteristic) { characteristics.put(characteristic.getUuid(),characteristic); return true; }
    public BluetoothGattCharacteristic getCharacteristic(UUID uuid) { return characteristics.get(uuid); }
}
