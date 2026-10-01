package dev.ghost.nearbyim.storage;

import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;

/** Exercises production authorization state, including queued-write cancellation. */
public final class TrustPolicyTests {
    private static int checks;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private static String key() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC"); generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair pair = generator.generateKeyPair(); byte[] challenge = new byte[]{1,3,5,7};
        Signature proof = Signature.getInstance("SHA256withECDSA"); proof.initSign(pair.getPrivate()); proof.update(challenge); byte[] signature = proof.sign();
        proof.initVerify(pair.getPublic()); proof.update(challenge); check(proof.verify(signature), "fixture must possess its real signing key");
        return Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
    }
    public static void main(String[] args) throws Exception {
        String pinned = key(), other = key();
        TrustPolicy policy = new TrustPolicy(); AtomicInteger writes = new AtomicInteger();
        TrustPolicy.Authorization outgoing = policy.begin("alice", pinned, null, true, true);
        check(outgoing.decision == TrustPolicy.Decision.APPROVE, "outgoing click grants initial consent");
        check(!policy.persist(outgoing, writes::incrementAndGet), "no trust before both sides ready");
        check(policy.ready(outgoing), "approved outgoing becomes ready");
        check(policy.persist(outgoing, writes::incrementAndGet) && writes.get() == 1, "successful remembered ready persists");
        check(!policy.persist(outgoing, writes::incrementAndGet), "ready callback cannot persist twice");
        TrustPolicy.Authorization unknown = policy.begin("bob", pinned, null, false, false);
        check(unknown.decision == TrustPolicy.Decision.ASK && !policy.ready(unknown), "history without pin needs incoming consent");
        check(policy.approve(unknown, false) && policy.ready(unknown), "only-this-time establishes current session");
        check(!policy.persist(unknown, writes::incrementAndGet), "only-this-time never grants future trust");
        TrustPolicy.Authorization trusted = policy.begin("alice", pinned, pinned, false, false);
        check(trusted.decision == TrustPolicy.Decision.APPROVE && policy.ready(trusted), "matching proven key autoapproves incoming");
        TrustPolicy.Authorization mismatch = policy.begin("alice", other, pinned, true, true);
        check(mismatch.decision == TrustPolicy.Decision.IDENTITY_CHANGED, "same UUID with different signing key is rejected");
        check(!policy.approve(mismatch, true) && !policy.ready(mismatch), "fresh click cannot overwrite a pin");
        TrustPolicy.Authorization revoked = policy.begin("alice", pinned, pinned, false, false);
        check(policy.ready(revoked), "prepare pending remembered-ready write");
        policy.revoke("alice");
        check(!policy.persist(revoked, writes::incrementAndGet), "revocation cancels queued remember writes");
        check(!policy.approve(revoked, true), "revocation invalidates delayed automatic approval");
        TrustPolicy.Authorization canceled = policy.begin("carol", pinned, null, true, true);
        policy.ready(canceled); policy.cancel(canceled);
        check(!policy.persist(canceled, writes::incrementAndGet), "closed or canceled sessions cannot remember");
        TrustPolicy.Authorization rejected = policy.begin("dave", pinned, null, false, false);
        policy.cancel(rejected);
        check(!policy.approve(rejected, true) && !policy.ready(rejected), "reject cancels delayed approval");
        TrustPolicy.Authorization failed = policy.begin("eve", pinned, null, true, true); policy.ready(failed);
        boolean thrown = false;
        try { policy.persist(failed, () -> { throw new IllegalStateException("disk full"); }); } catch (IllegalStateException expected) { thrown = true; }
        check(thrown, "storage failure propagates instead of reporting persisted trust");
        policy.cancel(failed);
        check(!policy.persist(failed, writes::incrementAndGet), "failed-and-closed session cannot retry trust late");
        check(writes.get() == 1, "all denied authorization paths leave storage unchanged");
        System.out.println("TrustPolicyTests: " + checks + " checks passed");
    }
}
