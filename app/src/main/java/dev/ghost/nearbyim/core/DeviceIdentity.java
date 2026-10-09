package dev.ghost.nearbyim.core;

import java.security.*;
import java.security.interfaces.ECPublicKey;
import java.security.spec.*;
import java.util.*;

/** Persistent P-256 signing identity. The private key may be a non-exportable Keystore handle. */
public final class DeviceIdentity {
    private final PrivateKey privateKey;
    private final String publicKey;

    public DeviceIdentity(KeyPair keys) {
        if (keys == null || keys.getPrivate() == null || !"EC".equalsIgnoreCase(keys.getPrivate().getAlgorithm()))
            throw new IllegalArgumentException("An EC signing key is required");
        try {
            byte[] encoded = keys.getPublic().getEncoded();
            decodePublicKey(encoded);
            publicKey = Base64.getEncoder().encodeToString(encoded);
        } catch (GeneralSecurityException | NullPointerException error) {
            throw new IllegalArgumentException("A canonical P-256 public key is required", error);
        }
        privateKey = keys.getPrivate();
    }

    public static DeviceIdentity generate() throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return new DeviceIdentity(generator.generateKeyPair());
    }

    public String publicKey() { return publicKey; }

    public byte[] sign(byte[] message) throws GeneralSecurityException {
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(privateKey); signer.update(message); return signer.sign();
    }

    /** Verify a session proof using the same strict canonical P-256 identity rules. */
    public static void verifyProof(byte[] publicKey,byte[] message,byte[] signature)throws GeneralSecurityException {
        if(message==null||message.length>4096||signature==null||signature.length==0||signature.length>80)
            throw new SignatureException("Invalid session proof size");
        Signature verifier=Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(decodePublicKey(publicKey));verifier.update(message);
        if(!verifier.verify(signature))throw new SignatureException("Session root proof does not verify");
    }

    static PublicKey decodePublicKey(byte[] encoded) throws GeneralSecurityException {
        if (encoded == null || encoded.length == 0 || encoded.length > 256)
            throw new InvalidKeyException("Invalid public key size");
        PublicKey key = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(encoded));
        if (!(key instanceof ECPublicKey) || !Arrays.equals(encoded, key.getEncoded()))
            throw new InvalidKeyException("Noncanonical EC public key");
        AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
        parameters.init(new ECGenParameterSpec("secp256r1"));
        ECParameterSpec required = parameters.getParameterSpec(ECParameterSpec.class);
        ECParameterSpec actual = ((ECPublicKey) key).getParams();
        if (!required.getCurve().equals(actual.getCurve()) || !required.getGenerator().equals(actual.getGenerator())
                || !required.getOrder().equals(actual.getOrder()) || required.getCofactor() != actual.getCofactor())
            throw new InvalidKeyException("Only P-256 keys are supported");
        return key;
    }
}
