package dev.ghost.nearbyim;
import android.app.Instrumentation;
import android.content.*;
import android.graphics.Bitmap;
import android.database.Cursor;
import android.os.*;
import android.provider.MediaStore;
import dev.ghost.nearbyim.core.*;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
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
        String originalName=String.join("",Collections.nCopies(80,"中"))+".png";
        AttachmentInfo info=AttachmentInfo.v2(UUID.randomUUID().toString(),originalName,"image/png",bytes.length,AttachmentInfo.hex(MessageDigest.getInstance("SHA-256").digest(bytes)),1);AttachmentRecord received=new AttachmentRecord(info,false,"received",info.size);android.net.Uri exported=null;int uiChecks=0;
        try{
            try(GalleryPublisher publisher=new GalleryPublisher(isolated)){
                publisher.automatic(false);if(publisher.export(peer,received,image,false).get(30,TimeUnit.SECONDS).state()!=null)throw new AssertionError("Disabled automatic export inserted a photo");
                var manual=publisher.export(peer,received,image,true).get(30,TimeUnit.SECONDS);exported=manual.uri();if(manual.state()!=PhotoExportStore.State.SAVED||exported==null)throw new AssertionError("Platform image was not published");
                uiChecks+=publishedName(base,exported);
                try(InputStream input=base.getContentResolver().openInputStream(exported)){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int n;while((n=input.read(buffer))!=-1)out.write(buffer,0,n);if(!Arrays.equals(bytes,out.toByteArray()))throw new AssertionError("Gallery export changed original bytes");}
                uiChecks+=AndroidSelectionUiChecks.run(instrumentation,exported);
                uiChecks+=supplementaryName(base,publisher,peer,image,bytes);
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
            if(!Files.exists(image)||!received.state.equals("received")||!received.info.name.equals(originalName))throw new AssertionError("Export filename/status changed private receipt ownership or original metadata");
            return 9+uiChecks;
        }finally{if(exported!=null)base.getContentResolver().delete(exported,null,null);try(var files=Files.walk(root)){for(Path file:files.sorted(Comparator.reverseOrder()).collect(java.util.stream.Collectors.toList()))Files.deleteIfExists(file);}}
    }
    private static int publishedName(Context context,android.net.Uri uri)throws Exception {
        try(Cursor cursor=context.getContentResolver().query(uri,new String[]{MediaStore.MediaColumns.DISPLAY_NAME},null,null,null)){
            if(cursor==null||!cursor.moveToFirst())throw new AssertionError("Published image filename was not queryable");
            String name=cursor.getString(0);
            if(name==null||!StandardCharsets.UTF_8.newEncoder().canEncode(name)||name.getBytes(StandardCharsets.UTF_8).length>255||!name.endsWith(".png"))throw new AssertionError("Published gallery filename is malformed or exceeds its UTF-8 component budget");
        }
        return 1;
    }
    private static int supplementaryName(Context context,GalleryPublisher publisher,String peer,Path original,byte[] bytes)throws Exception {
        String name=String.join("",Collections.nCopies(99,"a"))+"😀.png";
        AttachmentInfo info=AttachmentInfo.v2(UUID.randomUUID().toString(),name,"image/png",bytes.length,AttachmentInfo.hex(MessageDigest.getInstance("SHA-256").digest(bytes)),1);
        AttachmentRecord record=new AttachmentRecord(info,false,"received",info.size);android.net.Uri uri=null;
        try{
            GalleryPublisher.Result result=publisher.export(peer,record,original,true).get(30,TimeUnit.SECONDS);uri=result.uri();
            if(result.state()!=PhotoExportStore.State.SAVED||uri==null)throw new AssertionError("Supplementary filename could not be exported");
            int checks=publishedName(context,uri);
            try(InputStream input=context.getContentResolver().openInputStream(uri)){
                if(input==null)throw new AssertionError("Exported supplementary image was unavailable");
                ByteArrayOutputStream output=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int count;while((count=input.read(buffer))!=-1)output.write(buffer,0,count);
                if(!Arrays.equals(output.toByteArray(),bytes))throw new AssertionError("Filename shortening changed exported image bytes");
            }
            if(!record.info.name.equals(name)||!record.state.equals("received"))throw new AssertionError("Shortened gallery name changed attachment metadata or receipt");
            return checks+2;
        }finally{if(uri!=null)context.getContentResolver().delete(uri,null,null);}
    }
    private static void grant(Instrumentation test,Context target,String permission)throws IOException {try(var descriptor=test.getUiAutomation().executeShellCommand("pm grant "+target.getPackageName()+" "+permission);var input=new android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor)){byte[] buffer=new byte[1024];while(input.read(buffer)>0){}}}
}
