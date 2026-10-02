package dev.ghost.wozai;

import dev.ghost.nearbyim.core.StreamConnection;
import dev.ghost.nearbyim.i18n.LocalizedIOException;
import dev.ghost.nearbyim.i18n.UiText;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Windows classic Bluetooth RFCOMM using the Microsoft Bluetooth stack.
 * All operations except close() can block and must run off the Swing event thread.
 * The native library uses the same authenticated/encrypted service as Android.
 */
public final class WindowsBluetooth {
    public static final String SERVICE_UUID = "90c649e1-c095-4b22-8bc3-35e4c9c7b372";
    private static final String LIBRARY = "wozai_bluetooth";
    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    private static final String LOAD_FAILURE = loadLibrary();

    private WindowsBluetooth() { }

    /** supported distinguishes a missing platform/library from a missing or disabled radio. */
    public record Status(boolean supported, boolean available, String detail) { }
    public record Device(String address, String name, boolean paired) {
        public Device {
            address = normalizeAddress(address);
            name = name == null || name.isBlank() ? address : name;
        }
        public String routeKey() { return "bluetooth:" + address; }
    }

    public static boolean supported() { return LOAD_FAILURE == null; }
    public static boolean available() { return status().available(); }
    public static Status status() {
        if (LOAD_FAILURE != null) return new Status(false, false, LOAD_FAILURE);
        try {
            String failure = nativeStatus();
            return new Status(true, failure == null, failure == null ? "Windows Bluetooth RFCOMM is ready" : failure);
        } catch (IOException e) {
            return new Status(true, false, e.getMessage());
        }
    }

    /** Canonical MAC form; excludes the broadcast and unspecified Bluetooth addresses. */
    public static String normalizeAddress(String address) {
        Objects.requireNonNull(address, "address");
        if (!address.matches("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}"))
            throw new IllegalArgumentException("Bluetooth address must have the form AA:BB:CC:DD:EE:FF");
        String normalized = address.toUpperCase(Locale.ROOT);
        if (normalized.equals("00:00:00:00:00:00") || normalized.equals("FF:FF:FF:FF:FF:FF"))
            throw new IllegalArgumentException("Bluetooth address must identify a device");
        return normalized;
    }

    public static List<Device> scan() throws IOException { return scan(10); }
    /** Inquiry is bounded to 1..30 seconds, rounded down to the radio's 1.28 second units. */
    public static List<Device> scan(int inquirySeconds) throws IOException {
        if (inquirySeconds < 1 || inquirySeconds > 30) throw new IllegalArgumentException("Inquiry duration must be 1..30 seconds");
        requireAvailable();
        return List.of(nativeScan(inquirySeconds));
    }

    public static Server listen() throws IOException {
        requireAvailable();
        return new Server(nativeListen());
    }

    /** Allocate before connecting so another worker can close and cancel the pending attempt. */
    public static Connection openConnection(String address) throws IOException {
        String remote = normalizeAddress(address);
        requireAvailable();
        return new Connection(nativeOpen(), remote);
    }

    public static Connection connect(String address, int timeoutMillis) throws IOException {
        validateTimeout(timeoutMillis);
        Connection connection = openConnection(address);
        try {
            connection.connect(timeoutMillis);
            return connection;
        } catch (IOException | RuntimeException | Error e) {
            try { connection.close(); } catch (IOException closing) { e.addSuppressed(closing); }
            throw e;
        }
    }

    private static void requireAvailable() throws IOException {
        Status status = status();
        if (!status.available()) throw new LocalizedIOException(UiText.of("bluetoothUnavailable"));
    }
    private static void validateTimeout(int timeoutMillis) {
        if (timeoutMillis < 1 || timeoutMillis > 120_000)
            throw new IllegalArgumentException("Connection timeout must be 1..120000 milliseconds");
    }

    public static final class Server implements AutoCloseable {
        private final AtomicLong handle;
        private Server(long handle) { this.handle = new AtomicLong(handle); }
        public Connection accept() throws IOException {
            long accepted = nativeAccept(requireOpen(handle));
            try { return new Connection(accepted, nativeRemoteAddress(accepted)); }
            catch (IOException | RuntimeException | Error e) {
                try { nativeClose(accepted); } catch (IOException closing) { e.addSuppressed(closing); }
                throw e;
            }
        }
        /** Interrupts accept(); safe to call concurrently and more than once. */
        @Override public void close() throws IOException { closeHandle(handle); }
    }

    public static final class Connection implements StreamConnection {
        private final AtomicLong handle;
        private final String address;
        private final InputStream input = new InputStream() {
            @Override public int read() throws IOException {
                byte[] one = new byte[1];
                return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
            }
            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                Objects.checkFromIndexSize(offset, length, bytes.length);
                long current = requireOpen(handle);
                if (length == 0) return 0;
                return nativeRead(current, bytes, offset, length);
            }
            @Override public void close() throws IOException { Connection.this.close(); }
        };
        private final OutputStream output = new OutputStream() {
            @Override public void write(int value) throws IOException { write(new byte[] { (byte) value }, 0, 1); }
            @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                Objects.checkFromIndexSize(offset, length, bytes.length);
                long current = requireOpen(handle);
                if (length != 0) nativeWrite(current, bytes, offset, length);
            }
            @Override public void close() throws IOException { Connection.this.close(); }
        };

        private Connection(long handle, String address) {
            this.handle = new AtomicLong(handle);
            this.address = normalizeAddress(address);
        }
        /** Call once. close() interrupts a pending connection, including the pairing wait. */
        public void connect(int timeoutMillis) throws IOException {
            validateTimeout(timeoutMillis);
            nativeConnect(requireOpen(handle), address, timeoutMillis);
        }
        public String remoteAddress() { return address; }
        public String routeKey() { return "bluetooth:" + address; }
        @Override public InputStream input() throws IOException { nativeRequireConnected(requireOpen(handle)); return input; }
        @Override public OutputStream output() throws IOException { nativeRequireConnected(requireOpen(handle)); return output; }
        @Override public String label() { return "Bluetooth"; }
        /** Interrupts connect()/read()/write(); safe to call concurrently and more than once. */
        @Override public void close() throws IOException { closeHandle(handle); }
    }

    private static long requireOpen(AtomicLong handle) throws IOException {
        long value = handle.get();
        if (value == 0) throw new IOException("Bluetooth socket is closed");
        return value;
    }
    private static void closeHandle(AtomicLong handle) throws IOException {
        long value = handle.getAndSet(0);
        if (value != 0) nativeClose(value);
    }

    private static String loadLibrary() {
        if (!WINDOWS) return "Classic Bluetooth RFCOMM is available only on Windows";
        try {
            String override = System.getProperty("wozai.bluetooth.library", "");
            if (!override.isEmpty()) {
                Path path = Path.of(override);
                if (!path.isAbsolute()) return "wozai.bluetooth.library must be an absolute DLL path";
                System.load(path.toString());
            } else {
                // jpackage stores both the application JAR and the DLL in its app directory.
                Path source = Path.of(WindowsBluetooth.class.getProtectionDomain().getCodeSource().getLocation().toURI());
                Path directory = Files.isDirectory(source) ? source : source.getParent();
                Path adjacent = directory.resolve(LIBRARY + ".dll");
                if (Files.isRegularFile(adjacent)) System.load(adjacent.toAbsolutePath().toString());
                else System.loadLibrary(LIBRARY);
            }
            // Fail during initialization if the packaged DLL has an incompatible JNI API.
            if (nativeVersion() != 1) return "The Windows Bluetooth DLL has an incompatible version";
            return null;
        } catch (Exception | LinkageError e) {
            return "Windows Bluetooth DLL could not be loaded: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    private static native int nativeVersion();
    private static native String nativeStatus() throws IOException;
    private static native Device[] nativeScan(int inquirySeconds) throws IOException;
    private static native long nativeListen() throws IOException;
    private static native long nativeOpen() throws IOException;
    private static native void nativeConnect(long handle, String address, int timeoutMillis) throws IOException;
    private static native long nativeAccept(long handle) throws IOException;
    private static native String nativeRemoteAddress(long handle) throws IOException;
    private static native void nativeRequireConnected(long handle) throws IOException;
    private static native int nativeRead(long handle, byte[] bytes, int offset, int length) throws IOException;
    private static native void nativeWrite(long handle, byte[] bytes, int offset, int length) throws IOException;
    private static native void nativeClose(long handle) throws IOException;
}
