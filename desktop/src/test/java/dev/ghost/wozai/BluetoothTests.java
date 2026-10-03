package dev.ghost.wozai;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Locale;

/** Platform fallback and JNI validation tests; no physical Bluetooth radio is required. */
public final class BluetoothTests {
    private static int passed;
    private interface Throwing { void run() throws Exception; }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        passed++;
    }
    private static void expect(Class<? extends Throwable> type, Throwing operation) throws Exception {
        try { operation.run(); }
        catch (Throwable error) {
            if (type.isInstance(error)) { passed++; return; }
            throw new AssertionError("Expected " + type.getSimpleName() + ", got " + error, error);
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }
    private static Object nativeCall(String name, Class<?>[] types, Object... arguments) throws Exception {
        Method method = WindowsBluetooth.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        try { return method.invoke(null, arguments); }
        catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception exception) throw exception;
            if (cause instanceof Error error) throw error;
            throw e;
        }
    }

    public static void main(String[] arguments) throws Exception {
        check(WindowsBluetooth.normalizeAddress("ab:Cd:01:23:45:67").equals("AB:CD:01:23:45:67"), "Bluetooth MAC was not canonicalized");
        check(WindowsBluetooth.SERVICE_UUID.equals("90c649e1-c095-4b22-8bc3-35e4c9c7b372"), "Android service UUID changed");
        var device = new WindowsBluetooth.Device("ab:Cd:01:23:45:67", "手机", true);
        check(device.address().equals("AB:CD:01:23:45:67") && device.name().equals("手机") && device.paired(), "Bluetooth device metadata changed");
        check(device.routeKey().equals("bluetooth:AB:CD:01:23:45:67"), "Bluetooth route lost its transport");
        check(new WindowsBluetooth.Device("AB:CD:01:23:45:67", " ", false).name().equals("AB:CD:01:23:45:67"), "Unnamed device has no usable label");
        for (String invalid : new String[] { "", "127.0.0.1:4455", "bluetooth:AB:CD:01:23:45:67", "AB-CD-01-23-45-67", "AB:CD:01:23:45", "AB:CD:01:23:45:GG", " AB:CD:01:23:45:67", "00:00:00:00:00:00", "FF:FF:FF:FF:FF:FF" }) {
            expect(IllegalArgumentException.class, () -> WindowsBluetooth.normalizeAddress(invalid));
            expect(IllegalArgumentException.class, () -> WindowsBluetooth.openConnection(invalid));
        }
        expect(NullPointerException.class, () -> WindowsBluetooth.normalizeAddress(null));
        expect(IllegalArgumentException.class, () -> WindowsBluetooth.scan(0));
        expect(IllegalArgumentException.class, () -> WindowsBluetooth.scan(31));
        expect(IllegalArgumentException.class, () -> WindowsBluetooth.connect("AB:CD:01:23:45:67", 0));
        expect(IllegalArgumentException.class, () -> WindowsBluetooth.connect("AB:CD:01:23:45:67", 120001));

        var status = WindowsBluetooth.status();
        check(status.detail() != null && !status.detail().isBlank(), "Bluetooth status has no explanatory text");
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
        if (windows) {
            // A missing DLL must fail Windows CI even when the runner has no radio.
            check(status.supported(), "Packaged Windows JNI DLL failed to load: " + status.detail());
            check(((Integer) nativeCall("nativeVersion", new Class<?>[0])) == 2, "Native JNI API version mismatch");
            nativeValidation();
        } else {
            check(!status.supported() && !status.available(), "Non-Windows platform attempted RFCOMM");
            check(status.detail().contains("Windows"), "Non-Windows status did not explain the platform requirement");
        }
        if (!status.available()) {
            expect(IOException.class, WindowsBluetooth::listen);
            expect(IOException.class, () -> WindowsBluetooth.scan(1));
            expect(IOException.class, () -> WindowsBluetooth.openConnection("AB:CD:01:23:45:67"));
            expect(IOException.class, () -> WindowsBluetooth.connect("AB:CD:01:23:45:67", 100));
        }
        System.out.println("BluetoothTests: " + passed + " checks passed; physical RFCOMM pairing/Android transfer require a Windows radio and Android device");
    }

    private static void nativeValidation() throws Exception {
        Class<?>[] bufferTypes = { long.class, byte[].class, int.class, int.class };
        for (String operation : new String[] { "nativeRead", "nativeWrite" }) {
            expect(IllegalArgumentException.class, () -> nativeCall(operation, bufferTypes, 0L, null, 0, 1));
            expect(IllegalArgumentException.class, () -> nativeCall(operation, bufferTypes, 0L, new byte[4], -1, 1));
            expect(IllegalArgumentException.class, () -> nativeCall(operation, bufferTypes, 0L, new byte[4], 1, Integer.MAX_VALUE));
            expect(IllegalArgumentException.class, () -> nativeCall(operation, bufferTypes, 0L, new byte[4], 4, 1));
            expect(IOException.class, () -> nativeCall(operation, bufferTypes, Long.MAX_VALUE, new byte[4], 0, 1));
        }
        Class<?>[] scanTypes = { long.class, int.class };
        expect(IllegalArgumentException.class, () -> nativeCall("nativeScan", scanTypes, 0L, 0));
        expect(IOException.class, () -> nativeCall("nativeScan", scanTypes, Long.MAX_VALUE, 1));
        try (WindowsBluetooth.Inquiry inquiry = WindowsBluetooth.openInquiry()) {
            expect(IllegalArgumentException.class, () -> inquiry.scan(0));
            inquiry.close(); inquiry.close();
            expect(IOException.class, () -> inquiry.scan(1));
        }
        long old = (Long) nativeCall("nativeOpenInquiry", new Class<?>[0]);
        nativeCall("nativeCloseInquiry", new Class<?>[]{long.class}, old);
        nativeCall("nativeCloseInquiry", new Class<?>[]{long.class}, old);
        long next = (Long) nativeCall("nativeOpenInquiry", new Class<?>[0]);
        check(next > old, "Inquiry handles were reused");
        expect(IOException.class, () -> nativeCall("nativeScan", scanTypes, old, 1));
        nativeCall("nativeCloseInquiry", new Class<?>[]{long.class}, next);
        Class<?>[] connectTypes = { long.class, String.class, int.class };
        expect(IllegalArgumentException.class, () -> nativeCall("nativeConnect", connectTypes, 0L, "AB:CD:01:23:45:67", 0));
        expect(IllegalArgumentException.class, () -> nativeCall("nativeConnect", connectTypes, 0L, "GG:CD:01:23:45:67", 100));
        expect(IllegalArgumentException.class, () -> nativeCall("nativeConnect", connectTypes, 0L, "00:00:00:00:00:00", 100));
        expect(IllegalArgumentException.class, () -> nativeCall("nativeConnect", connectTypes, 0L, "FF:FF:FF:FF:FF:FF", 100));
        for (String operation : new String[] { "nativeAccept", "nativeRemoteAddress", "nativeRequireConnected" })
            expect(IOException.class, () -> nativeCall(operation, new Class<?>[] { long.class }, Long.MAX_VALUE));
        // Opaque IDs cannot be treated as pointers, including on close.
        nativeCall("nativeClose", new Class<?>[] { long.class }, Long.MAX_VALUE);
        nativeCall("nativeClose", new Class<?>[] { long.class }, Long.MAX_VALUE);
        passed++;
    }
}
