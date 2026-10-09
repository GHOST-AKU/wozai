package dev.ghost.nearbyim.core;

import java.io.*;

/** A task-scoped source. Returned streams own their view, not the source lifetime. */
public interface AttachmentSource extends AutoCloseable {
    long size();
    String generation();
    boolean seekable();
    InputStream open(long offset) throws IOException;
    void verifyUnchanged() throws IOException;
    void close() throws IOException;
}
