package org.nearbymesh.radio;
/** Exactly one physical transport per lab run. */
public interface RadioAdapter extends AutoCloseable {
    void start() throws Exception;
    @Override void close();
}
