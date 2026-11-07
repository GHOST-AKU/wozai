package android.bluetooth;

public final class BluetoothGatt {
    public static final int GATT_SUCCESS = 0, GATT_REQUEST_NOT_SUPPORTED = 6, GATT_READ_NOT_PERMITTED = 2;
    public int disconnects, closes, mtuRequests, discoveries, notifications, descriptorWrites, characteristicWrites;
    public BluetoothGattService service;
    public void disconnect() { disconnects++; }
    public void close() { closes++; }
    public boolean requestMtu(int mtu) { mtuRequests++; return true; }
    public boolean discoverServices() { discoveries++; return true; }
    public BluetoothGattService getService(java.util.UUID uuid) { return service; }
    public boolean setCharacteristicNotification(BluetoothGattCharacteristic characteristic, boolean enabled) { notifications++; return true; }
    public int writeDescriptor(BluetoothGattDescriptor descriptor, byte[] bytes) { descriptorWrites++; return 0; }
    public boolean writeDescriptor(BluetoothGattDescriptor descriptor) { descriptorWrites++; return true; }
    public int writeCharacteristic(BluetoothGattCharacteristic characteristic, byte[] bytes, int type) { characteristicWrites++; return 0; }
    public boolean writeCharacteristic(BluetoothGattCharacteristic characteristic) { characteristicWrites++; return true; }
}
