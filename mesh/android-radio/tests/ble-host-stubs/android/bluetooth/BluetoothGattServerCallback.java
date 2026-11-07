package android.bluetooth;

public abstract class BluetoothGattServerCallback {
    public void onServiceAdded(int status, BluetoothGattService service) { }
    public void onConnectionStateChange(BluetoothDevice device, int status, int state) { }
    public void onMtuChanged(BluetoothDevice device, int mtu) { }
    public void onDescriptorWriteRequest(BluetoothDevice device, int request, BluetoothGattDescriptor descriptor,
            boolean prepared, boolean responseNeeded, int offset, byte[] value) { }
    public void onDescriptorReadRequest(BluetoothDevice device, int request, int offset, BluetoothGattDescriptor descriptor) { }
    public void onCharacteristicWriteRequest(BluetoothDevice device, int request, BluetoothGattCharacteristic characteristic,
            boolean prepared, boolean responseNeeded, int offset, byte[] value) { }
    public void onCharacteristicReadRequest(BluetoothDevice device, int request, int offset, BluetoothGattCharacteristic characteristic) { }
    public void onExecuteWrite(BluetoothDevice device, int request, boolean execute) { }
    public void onNotificationSent(BluetoothDevice device, int status) { }
}
