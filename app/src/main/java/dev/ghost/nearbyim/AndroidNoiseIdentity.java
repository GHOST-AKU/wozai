package dev.ghost.nearbyim;

import android.content.Context;
import android.security.keystore.*;
import android.util.AtomicFile;
import dev.ghost.nearbyim.core.DeviceIdentity;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

/** X25519 private bytes wrapped by a nonexportable Android Keystore AES key. */
public final class AndroidNoiseIdentity {
    private static final int MAGIC=0x4e4b5634;
    private static final String ALIAS="nearby-im-noise-wrapping-v4";
    private AndroidNoiseIdentity(){}
    public static byte[] load(Context context,DeviceIdentity root)throws IOException,GeneralSecurityException {
        return load(root,new File(context.getNoBackupFilesDir(),"noise-static-v4.bin"),ALIAS);
    }
    static synchronized byte[] load(DeviceIdentity root,File file,String alias)throws IOException,GeneralSecurityException {
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);AtomicFile atomic=new AtomicFile(file);
        boolean existing=file.exists()||new File(file+".bak").exists();
        if(!store.containsAlias(alias)) {
            if(existing)throw new KeyStoreException("Noise wrapping key unavailable");
            KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setUserAuthenticationRequired(false).build());generator.generateKey();
        }
        Key key=store.getKey(alias,null);byte[] aad=("wozai-noise-static-v4\0"+root.publicKey()).getBytes(StandardCharsets.US_ASCII);
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        if(existing) {
            byte[] iv=new byte[12],encrypted=new byte[48];
            try(DataInputStream input=new DataInputStream(atomic.openRead())) {
                if(input.readInt()!=MAGIC||input.readUnsignedByte()!=1)throw new IOException("Invalid Noise key file");input.readFully(iv);
                if(input.readInt()!=encrypted.length)throw new IOException("Invalid encrypted key size");input.readFully(encrypted);if(input.read()!=-1)throw new IOException("Trailing encrypted key bytes");
            }
            cipher.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,iv));cipher.updateAAD(aad);byte[] clear=cipher.doFinal(encrypted);
            if(clear.length!=32){Arrays.fill(clear,(byte)0);throw new GeneralSecurityException("Invalid Noise private key");}return clear;
        }
        byte[] privateKey=new byte[32];new SecureRandom().nextBytes(privateKey);FileOutputStream output=null;
        try {
            cipher.init(Cipher.ENCRYPT_MODE,key);cipher.updateAAD(aad);byte[] encrypted=cipher.doFinal(privateKey),iv=cipher.getIV();
            if(iv.length!=12||encrypted.length!=48)throw new GeneralSecurityException("Unsupported key wrapper output");
            output=atomic.startWrite();DataOutputStream data=new DataOutputStream(output);data.writeInt(MAGIC);data.writeByte(1);data.write(iv);data.writeInt(encrypted.length);data.write(encrypted);data.flush();atomic.finishWrite(output);output=null;
            return privateKey.clone();
        }finally{if(output!=null)atomic.failWrite(output);Arrays.fill(privateKey,(byte)0);}
    }
}
