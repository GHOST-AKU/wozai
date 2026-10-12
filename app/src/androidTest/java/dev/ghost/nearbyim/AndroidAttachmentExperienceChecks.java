package dev.ghost.nearbyim;
import android.content.*;
import android.net.Uri;
import java.util.*;

/** Native ClipData handling and gallery routing; the UI and MediaStore tests run in instrumentation. */
final class AndroidAttachmentExperienceChecks {
    static int run(Context context)throws Exception {
        Uri one=Uri.parse("content://test/photos/1"),two=Uri.parse("content://test/photos/2");
        Intent result=new Intent().setData(one);ClipData clip=ClipData.newRawUri("photos",one);clip.addItem(new ClipData.Item(two));clip.addItem(new ClipData.Item(one));result.setClipData(clip);
        if(!AttachmentSelection.from(result).equals(List.of(one,two)))throw new AssertionError("Native multi-selection lost order or duplicates");
        if(AttachmentSelection.photosIntent(context).getAction()!=null&&Intent.ACTION_OPEN_DOCUMENT.equals(AttachmentSelection.photosIntent(context).getAction()))throw new AssertionError("Photo entry still opens a document browser");
        boolean rejected=false;try{AttachmentSelection.from(new Intent().setData(Uri.parse("file:///tmp/private")));}catch(IllegalArgumentException expected){rejected=true;}
        if(!rejected)throw new AssertionError("Picker accepted an ungranted file URI");
        return 3;
    }
}
