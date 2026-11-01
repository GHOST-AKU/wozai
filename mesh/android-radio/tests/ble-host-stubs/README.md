These small original host doubles model only the Android API boundaries needed by
BleAdapterHostTest: virtual scheduling/time, indication submissions and callbacks.
The tests execute the real BleAdapter pump/ACK logic. They do not implement BLE,
model vendor timing or prove Android framework behavior. SDK36 supplies remaining
API signatures. test-ble-host.sh puts these doubles ahead of android.jar only on
the host test classpath. Production Java/DEX builds read src/ and never these files.
