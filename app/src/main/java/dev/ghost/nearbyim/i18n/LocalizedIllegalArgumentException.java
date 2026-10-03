package dev.ghost.nearbyim.i18n;

import java.util.Objects;

public final class LocalizedIllegalArgumentException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;
    public final UiText text;
    public LocalizedIllegalArgumentException(UiText text) { super(text.key); this.text = Objects.requireNonNull(text); }
}
