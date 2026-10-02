package dev.ghost.nearbyim.i18n;

import java.io.IOException;
import java.util.Objects;

/** Typed user-facing failure; getMessage() is a diagnostic key, never translated prose. */
public final class LocalizedIOException extends IOException {
    private static final long serialVersionUID = 1L;
    public final UiText text;
    public LocalizedIOException(UiText text) { super(text.key); this.text = Objects.requireNonNull(text); }
    public LocalizedIOException(UiText text, Throwable cause) { super(text.key, cause); this.text = Objects.requireNonNull(text); }
}
