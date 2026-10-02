package dev.ghost.wozai;

import dev.ghost.nearbyim.core.DeviceIdentity;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.spec.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

public final class DesktopIdentity {
    public record Identity(String id, DeviceIdentity signer) { }
    public static boolean windows() { return System.getProperty("os.name").startsWith("Windows"); }
    public static Identity load(Path file) throws IOException {
        try {
            Properties values;
            if (!Files.exists(file)) {
                KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
                generator.initialize(new ECGenParameterSpec("secp256r1"));
                KeyPair keys = generator.generateKeyPair();
                values = new Properties();
                values.setProperty("id", UUID.randomUUID().toString());
                values.setProperty("public", Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()));
                byte[] encoded = keys.getPrivate().getEncoded();
                try { values.setProperty("private", Base64.getEncoder().encodeToString(windows() ? dpapi(encoded, true) : encoded)); }
                finally { Arrays.fill(encoded, (byte) 0); }
                values.setProperty("protection", windows() ? "dpapi" : "posix");
                AtomicFiles.write(file, values);
            } else values = AtomicFiles.read(file);
            String id = AtomicFiles.required(values, "id");
            if (!UUID.fromString(id).toString().equals(id)) throw new IOException("Invalid identity UUID");
            String protection = AtomicFiles.required(values, "protection");
            if (!protection.equals(windows() ? "dpapi" : "posix")) throw new IOException("Identity belongs to another platform");
            KeyFactory factory = KeyFactory.getInstance("EC");
            PublicKey publicKey = factory.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(AtomicFiles.required(values, "public"))));
            byte[] bytes = Base64.getDecoder().decode(AtomicFiles.required(values, "private"));
            if (windows()) bytes = dpapi(bytes, false);
            PrivateKey privateKey;
            try { privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(bytes)); }
            finally { Arrays.fill(bytes, (byte) 0); }
            DeviceIdentity signer = new DeviceIdentity(new KeyPair(publicKey, privateKey));
            byte[] probe = new byte[32]; new SecureRandom().nextBytes(probe);
            Signature verifier = Signature.getInstance("SHA256withECDSA"); verifier.initVerify(publicKey); verifier.update(probe);
            if (!verifier.verify(signer.sign(probe))) throw new IOException("Identity keys do not match");
            return new Identity(id, signer);
        } catch (GeneralSecurityException | IllegalArgumentException e) { throw new IOException("Unable to load device identity; existing identity was preserved", e); }
    }
    private static byte[] dpapi(byte[] bytes, boolean protect) throws IOException {
        String command = "$ErrorActionPreference='Stop'; Add-Type -AssemblyName System.Security; "
                + "$b=[Convert]::FromBase64String([Console]::In.ReadToEnd()); "
                + "$r=[Security.Cryptography.ProtectedData]::" + (protect ? "Protect" : "Unprotect")
                + "($b,$null,[Security.Cryptography.DataProtectionScope]::CurrentUser); "
                + "[Console]::Out.Write([Convert]::ToBase64String($r))";
        // Use the system binary, not an executable from a writable PATH directory.
        Path powershell = Path.of(System.getenv("SystemRoot"), "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
        Process process = new ProcessBuilder(powershell.toString(), "-NoLogo", "-NoProfile", "-NonInteractive", "-Command", command).start();
        try {
            try (OutputStream input = process.getOutputStream()) { input.write(Base64.getEncoder().encode(bytes)); }
            if (!process.waitFor(15, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IOException("Windows key protection timed out"); }
            if (process.exitValue() != 0) throw new IOException("Windows could not protect or unlock the identity");
            return Base64.getDecoder().decode(new String(process.getInputStream().readNBytes(16384), StandardCharsets.US_ASCII).trim());
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
        finally { process.destroy(); }
    }
}
