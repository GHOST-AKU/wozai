package dev.ghost.nearbyim.core;
import java.security.GeneralSecurityException;

/** Immutable UI snapshot; controllers recheck its roots and connection generation on save. */
public final class IdentityVerification {
    public final String peerId,localRoot,remoteRoot,generation;
    public final SafetyCode code;
    public final boolean verified;
    public IdentityVerification(String peerId,String localRoot,String remoteRoot,String generation,boolean verified)throws GeneralSecurityException {
        this.peerId=peerId;this.localRoot=localRoot;this.remoteRoot=remoteRoot;this.generation=generation;this.verified=verified;
        code=new SafetyCode(localRoot,remoteRoot);
    }
    public boolean sameIdentity(IdentityVerification other){return other!=null&&peerId.equals(other.peerId)&&localRoot.equals(other.localRoot)&&remoteRoot.equals(other.remoteRoot)&&generation.equals(other.generation);}
}
