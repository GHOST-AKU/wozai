package dev.ghost.nearbyim.i18n;

import java.io.Serializable;
import java.util.Arrays;
import java.util.Objects;

/** A display message remains in its original language-independent form until rendered. */
public final class UiText implements Serializable {
    private static final long serialVersionUID = 1L;
    public static final UiText EMPTY = new UiText("", new Object[0]);
    public final String key;
    public final Object[] arguments;
    private UiText(String key, Object[] arguments) {
        this.key = Objects.requireNonNull(key);
        this.arguments = arguments.clone();
    }
    public static UiText of(String key, Object... arguments) { return new UiText(key, arguments); }
    public boolean isEmpty() { return key.isEmpty(); }
    @Override public boolean equals(Object value) {
        if (!(value instanceof UiText)) return false;
        UiText other = (UiText) value;
        return key.equals(other.key) && Arrays.deepEquals(arguments, other.arguments);
    }
    @Override public int hashCode() { return 31 * key.hashCode() + Arrays.deepHashCode(arguments); }
    @Override public String toString() { return key; }
}
