package android.bluetooth;

public abstract class BluetoothGattCallback {
    public void onConnectionStateChange(BluetoothGatt gatt, int status, int state) { }
    public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) { }
    public void onServicesDiscovered(BluetoothGatt gatt, int status) { }
    public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) { }
    public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) { }
    public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, byte[] bytes) { }
    public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) { }
}
