package dev.ghost.nearbyim;
import android.content.Context;
import dev.ghost.nearbyim.core.DeviceIdentity;
import java.io.*;
import java.security.*;
import java.util.*;
final class AndroidNoiseIdentityChecks {
    static int run(Context context)throws Exception {
        File directory=new File(context.getCacheDir(),"noise-key-check-"+UUID.randomUUID());if(!directory.mkdir())throw new IOException("Fixture directory unavailable");
        File file=new File(directory,"key.bin");String alias="nearby-im-noise-check-"+UUID.randomUUID();DeviceIdentity root=DeviceIdentity.generate();byte[] a=null,b=null;
        try {
            a=AndroidNoiseIdentity.load(root,file,alias);b=AndroidNoiseIdentity.load(root,file,alias);
            if(a.length!=32||!Arrays.equals(a,b))throw new AssertionError("Noise key changes across loads");
            byte[] stored=new byte[(int)file.length()];try(FileInputStream input=new FileInputStream(file)){new DataInputStream(input).readFully(stored);}
            for(int i=0;i<=stored.length-a.length;i++){boolean match=true;for(int j=0;j<a.length;j++)if(stored[i+j]!=a[j]){match=false;break;}if(match)throw new AssertionError("Plaintext Noise key is on disk");}
            boolean rejected=false;try{AndroidNoiseIdentity.load(DeviceIdentity.generate(),file,alias);}catch(GeneralSecurityException expected){rejected=true;}
            if(!rejected||!Arrays.equals(a,AndroidNoiseIdentity.load(root,file,alias)))throw new AssertionError("Wrong root accepted or existing key reset");
            try(RandomAccessFile output=new RandomAccessFile(file,"rw")){output.seek(file.length()-1);output.write(stored[stored.length-1]^1);}
            rejected=false;try{AndroidNoiseIdentity.load(root,file,alias);}catch(GeneralSecurityException expected){rejected=true;}
            if(!rejected)throw new AssertionError("Corrupt key accepted");
            return 4;
        }finally{if(a!=null)Arrays.fill(a,(byte)0);if(b!=null)Arrays.fill(b,(byte)0);KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);store.deleteEntry(alias);file.delete();new File(file+".bak").delete();directory.delete();}
    }
}
