package org.nearbymesh.radio;
/** Metadata only: peer is a configured lab label, never a MAC, IP, body or secret. */
public interface RadioEvents {
    void emit(String event,String peer,String reason,long tx,long rx);
}
