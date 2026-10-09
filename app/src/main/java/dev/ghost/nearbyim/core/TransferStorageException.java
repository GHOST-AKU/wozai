package dev.ghost.nearbyim.core;
import java.io.IOException;
/** Distinguishes the app's quota from filesystem free-space failures. */
public final class TransferStorageException extends IOException {
    public enum Reason { QUOTA, FREE_SPACE }
    public final Reason reason;
    public TransferStorageException(Reason reason){super("Attachment storage: "+reason);this.reason=reason;}
}
