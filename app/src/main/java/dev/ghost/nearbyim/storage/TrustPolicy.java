package dev.ghost.nearbyim.storage;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** A ready session may remember only its still-current consent and proven key. */
public final class TrustPolicy {
    public enum Decision { APPROVE, ASK, IDENTITY_CHANGED }
    public static final class Authorization {
        public final Decision decision;
        public final String peerId, publicKey;
        private final long revision;
        private boolean approved, remember, ready, canceled, persisted;
        private Authorization(String id, String key, long revision, Decision decision, boolean remember) {
            peerId = id; publicKey = key; this.revision = revision; this.decision = decision;
            approved = decision == Decision.APPROVE; this.remember = remember;
        }
    }
    private final Map<String, Long> revisions = new HashMap<>();
    private long version;
    public synchronized Authorization begin(String id, String key, String pin, boolean outgoing, boolean remember) {
        if (id == null || id.isEmpty() || key == null || key.isEmpty()) throw new IllegalArgumentException("Proven identity required");
        boolean matched = Objects.equals(key, pin);
        Decision decision = pin != null && !matched ? Decision.IDENTITY_CHANGED : outgoing || matched ? Decision.APPROVE : Decision.ASK;
        return new Authorization(id, key, peerRevision(id), decision, matched || (outgoing && remember));
    }
    private boolean current(Authorization authorization) {
        return authorization != null && !authorization.canceled && authorization.revision == peerRevision(authorization.peerId);
    }
    public synchronized boolean approve(Authorization authorization, boolean remember) {
        if (!current(authorization) || authorization.decision == Decision.IDENTITY_CHANGED) return false;
        authorization.approved = true; authorization.remember = remember; return true;
    }
    public synchronized boolean ready(Authorization authorization) {
        if (!current(authorization) || !authorization.approved) return false;
        authorization.ready = true; return true;
    }
    /** Holding this boundary through the write makes revoke and remember linearizable. */
    public synchronized boolean persist(Authorization authorization, Runnable write) {
        if (!current(authorization) || !authorization.approved || !authorization.ready || !authorization.remember || authorization.persisted) return false;
        write.run(); authorization.persisted = true; version++; return true;
    }
    public synchronized void cancel(Authorization authorization) { if (authorization != null) authorization.canceled = true; }
    public synchronized void revoke(String id) { revisions.put(id, peerRevision(id) + 1); version++; }
    public synchronized long peerRevision(String id) { return revisions.getOrDefault(id, 0L); }
    public synchronized long version() { return version; }
}
