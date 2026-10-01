package dev.ghost.nearbyim.core;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public interface StreamConnection extends Closeable {
    InputStream input() throws IOException;
    OutputStream output() throws IOException;
    String label();
}
