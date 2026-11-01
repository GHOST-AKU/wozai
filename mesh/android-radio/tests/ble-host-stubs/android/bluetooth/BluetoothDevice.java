package android.bluetooth;

import android.content.Context;
import android.os.Handler;

public final class BluetoothDevice {
    public static final int TRANSPORT_LE = 2, PHY_LE_1M_MASK = 1;
    private final String address;
    public BluetoothDevice(String address) { this.address = address; }
    public String getAddress() { return address; }
    public BluetoothGatt connectGatt(Context context, boolean autoConnect, BluetoothGattCallback callback,
            int transport, int phy, Handler handler) { throw new UnsupportedOperationException(); }
}
