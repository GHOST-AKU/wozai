package dev.ghost.nearbyim;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import dev.ghost.nearbyim.core.DeviceIdentity;
import java.io.IOException;
import java.security.*;
import java.security.cert.Certificate;
import java.security.spec.ECGenParameterSpec;

/** Stable non-exportable signing key; a reset requires deliberate trust rebinding. */
public final class AndroidIdentity {
    private static final String ALIAS = "nearby-im-device-signing-v1";
    private AndroidIdentity() {}
    public static DeviceIdentity load() throws GeneralSecurityException {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        try { store.load(null); } catch (IOException e) { throw new GeneralSecurityException("Cannot load device identity", e); }
        if (!store.containsAlias(ALIAS)) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore");
            generator.initialize(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256).setUserAuthenticationRequired(false).build());
            generator.generateKeyPair();
        }
        Key privateKey = store.getKey(ALIAS, null); Certificate certificate = store.getCertificate(ALIAS);
        if (!(privateKey instanceof PrivateKey) || certificate == null) throw new GeneralSecurityException("Device signing key unavailable");
        return new DeviceIdentity(new KeyPair(certificate.getPublicKey(), (PrivateKey) privateKey));
    }
}
