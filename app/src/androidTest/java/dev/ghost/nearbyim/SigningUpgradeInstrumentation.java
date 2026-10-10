package dev.ghost.nearbyim;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.LocaleManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Build;
import android.os.Bundle;
import android.os.LocaleList;
import android.util.Base64;
import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.UUID;
import org.json.JSONObject;

/** Platform-only checks also run against the unmodified, minified 0.3.1 APK. */
public final class SigningUpgradeInstrumentation extends Instrumentation {
    private static final String CERTIFICATE = "47a10fd5a882ab26c9a11fcc61df3b41f7c41017746fc6840cfbe581ca52c1dd";
    private static final String DEVICE_ALIAS = "nearby-im-device-signing-v1";
    private static final String BODY = "签名升级保留消息 · 中文🙂 {0}";
    private static final byte[] PROBE = "signing-upgrade-private-file".getBytes(StandardCharsets.UTF_8);
    private Bundle arguments;
    private int checks;

    public void onCreate(Bundle arguments) { this.arguments = arguments; super.onCreate(arguments); start(); }
    public void onStart() {
        Bundle results = new Bundle(); int status = Activity.RESULT_OK;
        try {
            Context context = getTargetContext();
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNATURES);
            check(info.signatures.length == 1 && hex(MessageDigest.getInstance("SHA-256").digest(info.signatures[0].toByteArray())).equals(CERTIFICATE), "Installed certificate pin");
            check((info.applicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0, "Installed application is non-debuggable");
            check(info.versionCode == Integer.parseInt(arguments.getString("version_code")), "Installed version code");
            File snapshot = new File(context.getFilesDir(), "signing-upgrade-snapshot.json");
            if ("seed".equals(arguments.getString("phase"))) seed(context, snapshot);
            else if ("verify".equals(arguments.getString("phase"))) verify(context, snapshot);
            else if ("noise".equals(arguments.getString("phase"))) { verify(context,snapshot); verifyNoise(context,snapshot); }
            else throw new IllegalArgumentException("Use phase=seed or phase=verify");
            results.putString("stream", "Signing upgrade " + arguments.getString("phase") + ": " + checks + " checks passed\n");
            results.putString("scope", "Persisted messages, conversation, identity preferences, AndroidKeyStore, trust, UI settings and private file. In-memory drafts are outside this acceptance.");
        } catch (Throwable failure) {
            status = Activity.RESULT_CANCELED;
            StringWriter trace = new StringWriter(); failure.printStackTrace(new PrintWriter(trace)); results.putString("stream", trace.toString());
        }
        finish(status, results);
    }

    private void seed(Context context, File snapshot) throws Exception {
        check(!snapshot.exists(), "Refuse to replace an existing test snapshot");
        SharedPreferences identity = context.getSharedPreferences("identity", Context.MODE_PRIVATE);
        check(identity.getString("id", "").length() == 36, "Baseline app created a device ID");
        KeyStore keys = keys();
        check(keys.containsAlias(DEVICE_ALIAS), "Baseline app created its AndroidKeyStore identity");
        String peer = UUID.randomUUID().toString(), message = UUID.randomUUID().toString();
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        String publicKey = Base64.encodeToString(generator.generateKeyPair().getPublic().getEncoded(), Base64.NO_WRAP);
        try (SQLiteDatabase database = database(context)) {
            check(database.getVersion() == 4, "Baseline database schema");
            for (String table : new String[]{"messages", "conversations", "trusted_devices"}) {
                try (Cursor cursor = database.rawQuery("SELECT count(*) FROM " + table, null)) {
                    check(cursor.moveToFirst() && cursor.getInt(0) == 0, "Refuse to modify existing user " + table);
                }
            }
            database.beginTransaction();
            try {
                database.execSQL("INSERT INTO conversations VALUES (?,?,?)", new Object[]{peer, "升级测试好友", 1000});
                database.execSQL("INSERT INTO messages(peer_id,id,body,outgoing,state,time,received) VALUES (?,?,?,?,?,?,?)",
                        new Object[]{peer, message, BODY, 1, "delivered", 1000, 1000});
                database.execSQL("INSERT INTO trusted_devices VALUES (?,?,?,?,?,?)", new Object[]{peer, publicKey, "升级测试好友", 1000, 1, null});
                database.setTransactionSuccessful();
            } finally { database.endTransaction(); }
        }
        check(identity.edit().putString("nickname", "签名升级用户").commit(), "Persist fixture nickname");
        check(context.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putString("language", "en").putBoolean("rememberNext", false).commit(), "Persist UI settings");
        if (Build.VERSION.SDK_INT >= 33) context.getSystemService(LocaleManager.class).setApplicationLocales(LocaleList.forLanguageTags("en"));
        try (FileOutputStream stream = context.openFileOutput("signing-upgrade-persisted.bin", Context.MODE_PRIVATE)) { stream.write(PROBE); stream.getFD().sync(); }
        JSONObject saved = new JSONObject();
        saved.put("uid", context.getApplicationInfo().uid); saved.put("device_id", identity.getString("id", null));
        saved.put("device_public_key", Base64.encodeToString(keys.getCertificate(DEVICE_ALIAS).getPublicKey().getEncoded(), Base64.NO_WRAP));
        saved.put("peer", peer); saved.put("message", message); saved.put("peer_public_key", publicKey);
        saved.put("signature", Base64.encodeToString(sign(keys), Base64.NO_WRAP));
        try (FileOutputStream stream = new FileOutputStream(snapshot)) { stream.write(saved.toString().getBytes(StandardCharsets.UTF_8)); stream.getFD().sync(); }
    }

    private void verify(Context context, File snapshot) throws Exception {
        check(snapshot.isFile(), "Baseline snapshot survived replacement");
        JSONObject saved = new JSONObject(new String(Files.readAllBytes(snapshot.toPath()), StandardCharsets.UTF_8));
        check(context.getApplicationInfo().uid == saved.getInt("uid"), "Application UID preserved");
        SharedPreferences identity = context.getSharedPreferences("identity", Context.MODE_PRIVATE);
        check(saved.getString("device_id").equals(identity.getString("id", null)), "Device UUID preserved");
        check("签名升级用户".equals(identity.getString("nickname", null)), "Nickname preserved");
        KeyStore keys = keys();
        check(keys.containsAlias(DEVICE_ALIAS), "Original AndroidKeyStore alias preserved");
        check(saved.getString("device_public_key").equals(Base64.encodeToString(keys.getCertificate(DEVICE_ALIAS).getPublicKey().getEncoded(), Base64.NO_WRAP)), "Device public key unchanged");
        Signature verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(keys.getCertificate(DEVICE_ALIAS)); verifier.update(PROBE);
        check(verifier.verify(Base64.decode(saved.getString("signature"), Base64.NO_WRAP)), "Pre-upgrade identity proof verifies");
        byte[] proof = sign(keys); verifier.initVerify(keys.getCertificate(DEVICE_ALIAS)); verifier.update(PROBE);
        check(verifier.verify(proof), "Preserved private-key handle still signs");
        try (SQLiteDatabase database = database(context)) {
            check(database.getVersion() == 4, "Database schema preserved");
            try (Cursor cursor = database.rawQuery("SELECT body,state FROM messages WHERE peer_id=? AND id=?", new String[]{saved.getString("peer"), saved.getString("message")})) {
                check(cursor.moveToFirst() && BODY.equals(cursor.getString(0)) && "delivered".equals(cursor.getString(1)) && !cursor.moveToNext(), "Exact message and delivery state preserved");
            }
            try (Cursor cursor = database.rawQuery("SELECT name FROM conversations WHERE peer_id=?", new String[]{saved.getString("peer")})) {
                check(cursor.moveToFirst() && "升级测试好友".equals(cursor.getString(0)), "Conversation preserved");
            }
            try (Cursor cursor = database.rawQuery("SELECT public_key,mode FROM trusted_devices WHERE peer_id=?", new String[]{saved.getString("peer")})) {
                check(cursor.moveToFirst() && saved.getString("peer_public_key").equals(cursor.getString(0)) && cursor.getInt(1) == 1, "Trusted-device pin and mode preserved");
            }
        }
        SharedPreferences ui = context.getSharedPreferences("ui", Context.MODE_PRIVATE);
        check("en".equals(ui.getString("language", null)) && !ui.getBoolean("rememberNext", true), "UI preferences preserved");
        if (Build.VERSION.SDK_INT >= 33) check("en".equals(context.getSystemService(LocaleManager.class).getApplicationLocales().toLanguageTags()), "OS application locale preserved");
        check(java.util.Arrays.equals(PROBE, Files.readAllBytes(new File(context.getFilesDir(), "signing-upgrade-persisted.bin").toPath())), "Private-file bytes preserved");
    }

    private SQLiteDatabase database(Context context) { return SQLiteDatabase.openDatabase(context.getDatabasePath("nearby-im.db").getPath(), null, SQLiteDatabase.OPEN_READWRITE); }
    private void verifyNoise(Context context,File snapshot)throws Exception {
        check(keys().containsAlias("nearby-im-noise-wrapping-v4"),"New Noise wrapping alias exists");
        File encrypted=new File(context.getNoBackupFilesDir(),"noise-static-v4.bin");
        check(encrypted.isFile()&&encrypted.length()==69,"Noise identity is stored as a bounded wrapped record");
        String digest=hex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(encrypted.toPath())));
        JSONObject saved=new JSONObject(new String(Files.readAllBytes(snapshot.toPath()),StandardCharsets.UTF_8));
        if(saved.has("noise_wrapped_digest"))check(digest.equals(saved.getString("noise_wrapped_digest")),"Wrapped Noise identity unchanged after process restart");
        else {saved.put("noise_wrapped_digest",digest);try(FileOutputStream out=new FileOutputStream(snapshot)){out.write(saved.toString().getBytes(StandardCharsets.UTF_8));out.getFD().sync();}}
    }
    private KeyStore keys() throws Exception { KeyStore result = KeyStore.getInstance("AndroidKeyStore"); result.load(null); return result; }
    private byte[] sign(KeyStore keys) throws Exception { Signature signer = Signature.getInstance("SHA256withECDSA"); signer.initSign((PrivateKey) keys.getKey(DEVICE_ALIAS, null)); signer.update(PROBE); return signer.sign(); }
    private void check(boolean condition, String description) { if (!condition) throw new AssertionError(description); checks++; }
    private String hex(byte[] bytes) { StringBuilder text = new StringBuilder(); for (byte value : bytes) text.append(String.format(java.util.Locale.ROOT, "%02x", value & 255)); return text.toString(); }
}
