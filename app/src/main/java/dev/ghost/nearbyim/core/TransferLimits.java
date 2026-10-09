package dev.ghost.nearbyim.core;

import java.io.IOException;

/** Limits for attachment v2. Legacy attachment v1 keeps its own limits. */
public final class TransferLimits {
    public static final long MAX_FILE_BYTES=10_737_418_240L;
    public static final int DATA_BYTES=32*1024, BLOCK_BYTES=1024*1024;
    private TransferLimits(){}
    public static void validateRange(long size,long offset,int length) throws IOException {
        if(size<0||size>MAX_FILE_BYTES||offset<0||offset>size||length<0||length>size-offset)
            throw new IOException("Invalid attachment range");
    }
}
