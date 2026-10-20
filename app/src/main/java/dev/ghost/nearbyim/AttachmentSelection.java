package dev.ghost.nearbyim;

import android.content.*;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import dev.ghost.nearbyim.core.AttachmentBatchQueue;
import java.util.*;

/** Ordered, bounded URI results shared by all native pickers and the confirmation page. */
final class AttachmentSelection {
    static final String URIS="selectionUris";
    static List<Uri> from(Intent result){
        if(result==null)return List.of();LinkedHashSet<Uri> selected=new LinkedHashSet<>();
        if(result.getClipData()!=null){ClipData clip=result.getClipData();if(clip.getItemCount()>AttachmentBatchQueue.MAX_PENDING)throw new IllegalArgumentException("Selection exceeds bounds");for(int i=0;i<clip.getItemCount();i++)add(selected,clip.getItemAt(i).getUri());}
        else if(result.getData()!=null)add(selected,result.getData());
        return List.copyOf(selected);
    }
    private static void add(Set<Uri> selected,Uri uri){if(uri==null||!"content".equals(uri.getScheme())||uri.getAuthority()==null||uri.toString().length()>2048)throw new IllegalArgumentException("Invalid selected URI");selected.add(uri);if(selected.size()>AttachmentBatchQueue.MAX_PENDING)throw new IllegalArgumentException("Selection exceeds bounds");}
    static Intent result(List<Uri> uris){if(uris.isEmpty())return new Intent();ClipData data=ClipData.newRawUri("attachments",uris.get(0));for(int i=1;i<uris.size();i++)data.addItem(new ClipData.Item(uris.get(i)));Intent result=new Intent();result.setClipData(data);return result.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);}
    static Intent photosIntent(Context context){
        if(Build.VERSION.SDK_INT>=33){Intent picker=new Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*").putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX,Math.min(AttachmentBatchQueue.MAX_PENDING,MediaStore.getPickImagesMaxLimit()));if(picker.resolveActivity(context.getPackageManager())!=null)return picker;}
        return new Intent(context,GalleryActivity.class);
    }
    static Intent filesIntent(){return new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);}
}
