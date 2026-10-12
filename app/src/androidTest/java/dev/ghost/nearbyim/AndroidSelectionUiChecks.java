package dev.ghost.nearbyim;
import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Real recycling gallery and confirmation activities; no fake content resolver or UI widgets. */
final class AndroidSelectionUiChecks {
    static int run(Instrumentation test,Uri original)throws Exception {
        Context context=test.getTargetContext();String permission=Build.VERSION.SDK_INT>=33?"android.permission.READ_MEDIA_IMAGES":"android.permission.READ_EXTERNAL_STORAGE";
        try(var fd=test.getUiAutomation().executeShellCommand("pm grant "+context.getPackageName()+" "+permission);var input=new ParcelFileDescriptor.AutoCloseInputStream(fd)){byte[] bytes=new byte[1024];while(input.read(bytes)>0){}}
        Activity gallery=test.startActivitySync(new Intent(context,GalleryActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try{
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);boolean found=false;
            while(System.nanoTime()<deadline){test.waitForIdleSync();found=onMain(test,()->{GridView grid=field(gallery,"grid");for(int i=0;i<grid.getAdapter().getCount();i++)if(grid.getAdapter().getItem(i).toString().contains("id="+original.getLastPathSegment()+",")){grid.performItemClick(grid.getAdapter().getView(i,null,grid),i,grid.getAdapter().getItemId(i));return true;}return false;});if(found)break;Thread.sleep(50);}
            if(!found)throw new AssertionError("Native gallery did not show the published fixture");
            if(!onMain(test,()->((Button)field(gallery,"confirm")).isEnabled()))throw new AssertionError("Gallery selection did not enable confirmation");
            screenshot(test,context,"native-gallery-selection");
        }finally{test.runOnMainSync(gallery::finish);test.waitForIdleSync();}
        Uri second=original.buildUpon().appendQueryParameter("preview","second").build();
        Activity page=test.startActivitySync(new Intent(context,AttachmentSelectionActivity.class).putParcelableArrayListExtra(AttachmentSelection.URIS,new ArrayList<>(List.of(original,second))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try{test.waitForIdleSync();onMain(test,()->{ListView list=field(page,"list");list.performItemClick(list.getAdapter().getView(1,null,list),1,list.getAdapter().getItemId(1));button(page,"attachmentMoveUp").performClick();List<Uri> selected=field(page,"selected");if(!selected.equals(List.of(second,original)))throw new AssertionError("Preview move changed the wrong source");button(page,"attachmentRemove").performClick();if(!selected.equals(List.of(original)))throw new AssertionError("Preview removal sent a deselected source");return null;});screenshot(test,context,"native-attachment-confirmation");onMain(test,()->{button(page,"send").performClick();if(!page.isFinishing())throw new AssertionError("Confirm did not finish the preview");return null;});
        }finally{test.runOnMainSync(page::finish);test.waitForIdleSync();}
        return 5;
    }
    private static Button button(Activity activity,String key){String label=AndroidText.get(activity,key);return all(activity.getWindow().getDecorView()).stream().filter(v->v instanceof Button b&&label.contentEquals(b.getText())).map(v->(Button)v).findFirst().orElseThrow();}
    private static List<View> all(View root){List<View> views=new ArrayList<>();views.add(root);if(root instanceof ViewGroup group)for(int i=0;i<group.getChildCount();i++)views.addAll(all(group.getChildAt(i)));return views;}
    @SuppressWarnings("unchecked") private static <T>T field(Object owner,String name)throws Exception {Field field=owner.getClass().getDeclaredField(name);field.setAccessible(true);return (T)field.get(owner);}
    private interface Work<T>{T run()throws Exception;}
    private static <T>T onMain(Instrumentation test,Work<T> work)throws Exception {AtomicReference<T> result=new AtomicReference<>();AtomicReference<Throwable> error=new AtomicReference<>();test.runOnMainSync(()->{try{result.set(work.run());}catch(Throwable e){error.set(e);}});if(error.get()!=null)throw new Exception(error.get());return result.get();}
    private static void screenshot(Instrumentation test,Context context,String name)throws IOException {android.graphics.Bitmap image=test.getUiAutomation().takeScreenshot();if(image==null)throw new IOException("Native screenshot unavailable");File directory=context.getExternalFilesDir("native-screens");directory.mkdirs();try(OutputStream out=new FileOutputStream(new File(directory,name+".png"))){image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}finally{image.recycle();}}
}
