package dev.ghost.nearbyim.core;
import java.io.IOException;
/** Explicit wire-version mismatch, shown as an upgrade requirement. */
public final class UnsupportedProtocolException extends IOException {
    public UnsupportedProtocolException(){super("Both devices must upgrade to the attachment protocol");}
}
