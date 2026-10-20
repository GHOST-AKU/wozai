package dev.ghost.nearbyim.core;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.*;

/** One in-memory recovery lease; it survives a network session, never an application restart. */
public final class FileRouteResume {
    final Path directory;final List<TransferTaskKey> tasks;final Set<String> queued;
    final ClientAttachmentTransfers.Intent intent;
    final Predicate<TransferTaskKey> permitted;final Predicate<String> queuedPermitted;
    final AtomicBoolean claimed=new AtomicBoolean(),valid=new AtomicBoolean(true);
    FileRouteResume(Path directory,List<TransferTaskKey> tasks,Set<String> queued,ClientAttachmentTransfers.Intent intent){
        this.directory=directory;this.tasks=List.copyOf(tasks);this.queued=Set.copyOf(queued);this.intent=intent;
        permitted=intent::allowed;queuedPermitted=id->intent.allowed(id,true);
    }
    public boolean active(){return valid.get()&&intent.alive.get();}
    public void cancel(){valid.set(false);}
    public boolean empty(){return !active()||tasks.stream().noneMatch(permitted)&&queued.stream().noneMatch(queuedPermitted);}
}
