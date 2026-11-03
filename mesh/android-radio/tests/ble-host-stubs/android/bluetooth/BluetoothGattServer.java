package android.bluetooth;

import java.util.ArrayList;
import java.util.List;

/** Records API submissions without supplying acknowledgements. */
public final class BluetoothGattServer {
    public final List<String> indications = new ArrayList<>();
    public final List<byte[]> indicatedValues = new ArrayList<>();
    public final List<String> holds = new ArrayList<>();
    public boolean holdAccepted = true, serviceAccepted = true, responseAccepted = true;
    public int closes, responses;
    public BluetoothGattService service;
    public boolean connect(BluetoothDevice device, boolean autoConnect) {
        if (autoConnect) throw new AssertionError("must not create background reconnect");
        holds.add(device.getAddress());
        return holdAccepted;
    }
    public boolean addService(BluetoothGattService service) { this.service = service; return serviceAccepted; }
    public final List<String> cancellations = new ArrayList<>();
    public void cancelConnection(BluetoothDevice device) { cancellations.add(device.getAddress()); }
    public void close() { closes++; }
    public boolean sendResponse(BluetoothDevice device, int request, int status, int offset, byte[] value) { responses++; return responseAccepted; }
    public int notifyCharacteristicChanged(BluetoothDevice device, BluetoothGattCharacteristic characteristic,
            boolean confirm, byte[] value) {
        if (!confirm || value == null || value.length == 0) throw new AssertionError("invalid indication");
        indications.add(device.getAddress()); indicatedValues.add(value.clone());
        return BluetoothStatusCodes.SUCCESS;
    }
    public boolean notifyCharacteristicChanged(BluetoothDevice device, BluetoothGattCharacteristic characteristic,
            boolean confirm) { throw new UnsupportedOperationException(); }
}
