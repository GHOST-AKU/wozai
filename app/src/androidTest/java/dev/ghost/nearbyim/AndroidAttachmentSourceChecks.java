package dev.ghost.nearbyim;
import android.content.*;
import android.net.Uri;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import dev.ghost.nearbyim.core.*;
final class AndroidAttachmentSourceChecks {
    private static int checks;
    private static void check(boolean valid,String message){if(!valid)throw new AssertionError(message);checks++;}
    static int run(Context context)throws Exception {
        checks=0;ContentResolver resolver=context.getContentResolver();Uri base=Uri.parse("content://dev.ghost.nearbyim.test.sources");resolver.call(base,"reset",null,null);
        String generation,reference;
        try(AndroidAttachmentSource source=new AndroidAttachmentSource(context,base.buildUpon().path("/file").build());InputStream view=source.open(5)) {
            check(source.seekable()&&source.size()==8,"Regular provider is not seekable");check(view.read()==5&&view.read()==6&&view.read()==7&&view.read()==-1,"Provider offset bounds changed");generation=source.generation();reference=source.persistentReference();source.verifyUnchanged();
        }
        try(AndroidAttachmentSource source=new AndroidAttachmentSource(context,base.buildUpon().path("/file").build(),generation)) {check(reference.equals(source.persistentReference()),"Restored provider identity changed");}
        try(AndroidAttachmentSource source=new AndroidAttachmentSource(context,base.buildUpon().path("/slice").build());InputStream view=source.open(1)) {check(source.size()==3&&view.read()==3&&view.read()==4&&view.read()==-1,"Asset subrange leaked other bytes");}
        Path snapshots=Files.createTempDirectory(context.getCacheDir().toPath(),"provider-snapshot-");
        try(AndroidAttachmentSource source=new AndroidAttachmentSource(context,base.buildUpon().path("/pipe").build())) {
            check(!source.seekable()&&source.size()==-1,"Unknown pipe treated as stable file");try(FileAttachmentSource snapshot=AttachmentSourceSnapshot.create(source,snapshots,32)){check(Arrays.equals(Files.readAllBytes(snapshot.path()),new byte[]{8,9,10}),"Pipe snapshot bytes changed");}
        }finally{try(var paths=Files.walk(snapshots)){for(Path file:(Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator)Files.delete(file);}}
        try{new AndroidAttachmentSource(context,base.buildUpon().path("/denied").build());throw new AssertionError("Revoked source opened");}catch(IOException expected){checks++;}
        try(AndroidAttachmentSource source=new AndroidAttachmentSource(context,base.buildUpon().path("/file").build())) {
            resolver.call(base,"mutate",null,null);try{source.verifyUnchanged();throw new AssertionError("Same-size provider mutation accepted");}catch(IOException expected){checks++;}
        }
        resolver.call(base,"reset",null,null);return checks;
    }
}
