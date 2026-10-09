package dev.ghost.nearbyim.core;

import java.io.*;

/** A task-scoped source. Returned streams own their view, not the source lifetime. */
public interface AttachmentSource extends AutoCloseable {
    long size();
    String generation();
    boolean seekable();
    /** Private durable descriptor; empty sources require the user to select them again. */
    default String persistentReference(){return "";}
    /** Delete only source data owned by this transfer; user-selected originals remain untouched. */
    default void discard()throws IOException{}
    InputStream open(long offset) throws IOException;
    void verifyUnchanged() throws IOException;
    void close() throws IOException;
}
