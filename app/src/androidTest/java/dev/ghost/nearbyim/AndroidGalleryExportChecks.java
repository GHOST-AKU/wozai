package dev.ghost.nearbyim;
import android.app.Instrumentation;
import android.content.*;
import android.graphics.Bitmap;
import android.os.*;
import dev.ghost.nearbyim.core.*;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Actual platform publication, original bytes, crash reconciliation, deletion and manual retry. */
final class AndroidGalleryExportChecks {
    static int run(Instrumentation instrumentation)throws Exception {
        Context base=instrumentation.getTargetContext();if(Build.VERSION.SDK_INT<=28){grant(instrumentation,base,"android.permission.WRITE_EXTERNAL_STORAGE");grant(instrumentation,base,"android.permission.READ_EXTERNAL_STORAGE");}
        Path root=Files.createTempDirectory(base.getCacheDir().toPath(),"native-gallery-").toRealPath();String namespace=UUID.randomUUID().toString();
        Context isolated=new ContextWrapper(base){public File getFilesDir(){return root.toFile();}public SharedPreferences getSharedPreferences(String name,int mode){return base.getSharedPreferences(namespace+name,mode);}};
        String peer=UUID.randomUUID().toString();Path image=root.resolve("original.png");Bitmap bitmap=Bitmap.createBitmap(17,13,Bitmap.Config.ARGB_8888);bitmap.eraseColor(0xff256b51);try(OutputStream output=Files.newOutputStream(image)){bitmap.compress(Bitmap.CompressFormat.PNG,100,output);}bitmap.recycle();byte[] bytes=Files.readAllBytes(image);
        AttachmentInfo info=AttachmentInfo.v2(UUID.randomUUID().toString(),"原件.png","image/png",bytes.length,AttachmentInfo.hex(MessageDigest.getInstance("SHA-256").digest(bytes)),1);AttachmentRecord received=new AttachmentRecord(info,false,"received",info.size);android.net.Uri exported=null;int uiChecks=0;
        try{
            try(GalleryPublisher publisher=new GalleryPublisher(isolated)){
                publisher.automatic(false);if(publisher.export(peer,received,image,false).get(30,TimeUnit.SECONDS).state()!=null)throw new AssertionError("Disabled automatic export inserted a photo");
                var manual=publisher.export(peer,received,image,true).get(30,TimeUnit.SECONDS);exported=manual.uri();if(manual.state()!=PhotoExportStore.State.SAVED||exported==null)throw new AssertionError("Platform image was not published");
                try(InputStream input=base.getContentResolver().openInputStream(exported)){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int n;while((n=input.read(buffer))!=-1)out.write(buffer,0,n);if(!Arrays.equals(bytes,out.toByteArray()))throw new AssertionError("Gallery export changed original bytes");}
                uiChecks+=AndroidSelectionUiChecks.run(instrumentation,exported);
                if(!publisher.export(peer,received,image,true).get(30,TimeUnit.SECONDS).uri().equals(exported))throw new AssertionError("Repeated saved receipt duplicated a gallery image");
                new PhotoExportStore(root.resolve("gallery-exports")).save(peer,received,new PhotoExportStore.Entry(PhotoExportStore.State.COPIED,exported.toString()));
            }
            try(GalleryPublisher restarted=new GalleryPublisher(isolated)){
                if(!restarted.export(peer,received,image,true).get(30,TimeUnit.SECONDS).uri().equals(exported))throw new AssertionError("Published-before-marker crash created another image");
                restarted.automatic(true);base.getContentResolver().delete(exported,null,null);
                if(restarted.export(peer,received,image,false).get(30,TimeUnit.SECONDS).state()!=PhotoExportStore.State.MISSING)throw new AssertionError("Automatic export recreated a user-deleted photo");
                exported=restarted.export(peer,received,image,true).get(30,TimeUnit.SECONDS).uri();if(exported==null)throw new AssertionError("Manual retry did not restore the requested image");
                restarted.clearPeer(peer).get(30,TimeUnit.SECONDS);try(InputStream input=base.getContentResolver().openInputStream(exported)){if(input==null||input.read()<0)throw new AssertionError("Clear chat deleted the gallery image");}
            }
            if(!Files.exists(image)||!received.state.equals("received"))throw new AssertionError("Export failure/status changed private receipt ownership");
            return 9+uiChecks;
        }finally{if(exported!=null)base.getContentResolver().delete(exported,null,null);try(var files=Files.walk(root)){for(Path file:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(file);}}
    }
    private static void grant(Instrumentation test,Context target,String permission)throws IOException {try(var descriptor=test.getUiAutomation().executeShellCommand("pm grant "+target.getPackageName()+" "+permission);var input=new android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor)){byte[] buffer=new byte[1024];while(input.read(buffer)>0){}}}
}
