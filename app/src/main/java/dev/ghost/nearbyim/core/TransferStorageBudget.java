package dev.ghost.nearbyim.core;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Share one instance across snapshots and both transfer directions. */
public final class TransferStorageBudget {
    public static final long DEFAULT_QUOTA=32L*1024*1024*1024;
    private final Path root;
    private final long quota;
    private final Map<TransferTaskKey,Reservation> reservations=new HashMap<>();
    public TransferStorageBudget(Path root,long quota)throws IOException {
        if(quota<0||Files.isSymbolicLink(root))throw new IOException("Invalid storage budget");this.root=root;this.quota=quota;Files.createDirectories(root);
    }
    public synchronized long reservedBytes(){long total=0;for(Reservation reservation:reservations.values())total+=reservation.remaining;return total;}
    private long storedBytes()throws IOException {
        long total=0;try(var entries=Files.walk(root)) {
            for(Path path:(Iterable<Path>)entries::iterator){if(Files.isSymbolicLink(path))throw new IOException("Unsafe storage entry");if(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)){long size=Files.size(path);if(size>Long.MAX_VALUE-total)throw new IOException("Storage accounting overflow");total+=size;}}
        }return total;
    }
    public synchronized Reservation reserve(TransferTaskKey key,long totalSize,long currentStoredBytes)throws IOException {
        TransferLimits.validateRange(totalSize,currentStoredBytes,0);if(reservations.containsKey(key))throw new IOException("Storage task already reserved");
        long required=totalSize-currentStoredBytes,stored=storedBytes(),reserved=reservedBytes(),margin=Math.max(64L*1024*1024,(totalSize+99)/100),free=Files.getFileStore(root).getUsableSpace();
        if(stored>quota||reserved>quota-stored||required>quota-stored-reserved||reserved>free||required>free-reserved||margin>free-reserved-required)
            throw new IOException("Attachment exceeds available storage or quota");
        Reservation result=new Reservation(key,totalSize,currentStoredBytes);reservations.put(key,result);return result;
    }
    public final class Reservation implements AutoCloseable {
        private final TransferTaskKey key;
        private final long total;
        private long accounted,remaining;
        private boolean closed;
        private Reservation(TransferTaskKey key,long total,long stored){this.key=key;this.total=total;accounted=stored;remaining=total-stored;}
        public void written(long storedBytes)throws IOException {synchronized(TransferStorageBudget.this){if(closed||storedBytes<accounted||storedBytes>total)throw new IOException("Invalid storage reservation update");accounted=storedBytes;remaining=total-storedBytes;}}
        public void close(){synchronized(TransferStorageBudget.this){if(closed)return;closed=true;remaining=0;reservations.remove(key);}}
    }
}
