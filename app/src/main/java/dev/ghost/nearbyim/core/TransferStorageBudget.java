package dev.ghost.nearbyim.core;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Share one instance across snapshots and both transfer directions. */
public final class TransferStorageBudget {
    public static final long DEFAULT_QUOTA=32L*1024*1024*1024;
    private final Path root;
    private long quota;
    private final Map<TransferTaskKey,Reservation> reservations=new HashMap<>();
    public TransferStorageBudget(Path root,long quota)throws IOException {
        if(quota<0||Files.isSymbolicLink(root))throw new IOException("Invalid storage budget");this.root=root;this.quota=quota;Files.createDirectories(root);
    }
    public synchronized long reservedBytes(){long total=0;for(Reservation reservation:reservations.values())total+=reservation.remaining;return total;}
    public synchronized long quota(){return quota;}
    public synchronized void quota(long value)throws IOException {long stored=storedBytes(),reserved=reservedBytes();if(value<0||stored>value||reserved>value-stored)throw new TransferStorageException(TransferStorageException.Reason.QUOTA);quota=value;}
    public synchronized long snapshotAllowance(long declared)throws IOException {
        if(declared< -1||declared>TransferLimits.MAX_FILE_BYTES)throw new IOException("Invalid snapshot size");
        long stored=storedBytes(),reserved=reservedBytes(),available=quota-stored-reserved;
        if(available<0||declared>=0&&declared>available)throw new TransferStorageException(TransferStorageException.Reason.QUOTA);
        long free=Files.getFileStore(root).getUsableSpace()-reserved;
        long candidate=declared>=0?declared:Math.max(0,Math.min(TransferLimits.MAX_FILE_BYTES,Math.min(available,free-64L*1024*1024)));
        long margin=Math.max(64L*1024*1024,(candidate+99)/100);
        if(free<margin||declared>=0&&declared>free-margin)throw new TransferStorageException(TransferStorageException.Reason.FREE_SPACE);
        return declared>=0?declared:Math.min(candidate,free-margin);
    }
    private long storedBytes()throws IOException {
        long total=0;try(var entries=Files.walk(root)) {
            for(Path path:(Iterable<Path>)entries::iterator){if(Files.isSymbolicLink(path))throw new IOException("Unsafe storage entry");if(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)){long size=Files.size(path);if(size>Long.MAX_VALUE-total)throw new IOException("Storage accounting overflow");total+=size;}}
        }return total;
    }
    public synchronized Reservation reserve(TransferTaskKey key,long totalSize,long currentStoredBytes)throws IOException {
        TransferLimits.validateRange(totalSize,currentStoredBytes,0);if(key==null||reservations.containsKey(key))throw new IOException("Storage task already reserved or invalid");
        long required=totalSize-currentStoredBytes,stored=storedBytes(),reserved=reservedBytes(),margin=Math.max(64L*1024*1024,(totalSize+99)/100),free=Files.getFileStore(root).getUsableSpace();
        if(stored>quota||reserved>quota-stored||required>quota-stored-reserved)throw new TransferStorageException(TransferStorageException.Reason.QUOTA);
        if(reserved>free||required>free-reserved||margin>free-reserved-required)throw new TransferStorageException(TransferStorageException.Reason.FREE_SPACE);
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
