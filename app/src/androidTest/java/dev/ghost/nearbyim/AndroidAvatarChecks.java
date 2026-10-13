package dev.ghost.nearbyim;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.view.*;
import android.widget.*;
import dev.ghost.nearbyim.core.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Native avatar contract checks. Called by the shared native instrumentation runner. */
public final class AndroidAvatarChecks {
    private static final String GIF="R0lGODlhBAACAIEAAP8AAAAAAAAAAAAAACH/C05FVFNDQVBFMi4wAwEAAAAh+QQACgAAACwAAAAABAACAAAIBwABCBwoMCAAIfkEAQoAAQAsAAAAAAQAAgCBAAD/AAAAAAAAAAAACAcAAQgcKDAgADs=";
    private AndroidAvatarChecks() {}
    public static void requireImplementation() throws Exception {
        try {
            Class.forName("dev.ghost.nearbyim.AvatarImages", false, AndroidAvatarChecks.class.getClassLoader());
            Class.forName("dev.ghost.nearbyim.AvatarActivity", false, AndroidAvatarChecks.class.getClassLoader());
        } catch (ClassNotFoundException missing) {
            throw new AssertionError("Android local avatar decode, crop and exact PNG preview flow is missing", missing);
        }
    }
    public static void main(String[] arguments) throws Exception { requireImplementation(); }
    public static int run(Context context) throws Exception {
        requireImplementation();int checks=0;Path directory=Files.createTempDirectory(context.getCacheDir().toPath(),"avatars-native-");
        Bitmap original=Bitmap.createBitmap(320,160,Bitmap.Config.ARGB_8888);original.eraseColor(Color.RED);Canvas canvas=new Canvas(original);Paint paint=new Paint();paint.setColor(Color.BLUE);canvas.drawRect(160,0,320,160,paint);
        try {
            byte[] encoded=AvatarImages.encodeCrop(original,new RectF(80,0,240,160));AvatarSpec.PngInfo info=AvatarSpec.validatePng(encoded);
            check(info.width()==256&&info.height()==256&&encoded.length<=AvatarSpec.MAX_BYTES,"Square crop lost its dimensions or byte bound");checks++;
            Bitmap preview=AvatarImages.preview(encoded);
            try{check(preview.getPixel(16,128)==Color.RED&&preview.getPixel(240,128)==Color.BLUE,"Selected crop lost its moved source pixels");checks++;}finally{preview.recycle();}
            String first=identity(),second=identity();AvatarStore own=new AvatarStore(directory,first),remote=new AvatarStore(directory,second);
            AvatarSnapshot saved=own.save(0,encoded);check(Arrays.equals(saved.png(),encoded),"Saved avatar differs from final PNG preview bytes");checks++;
            remote.save(0,encoded);remote.reset(1);check(Arrays.equals(own.snapshot().png(),encoded)&&own.snapshot().profile().revision==1,"Other device's avatar reset changed the local avatar");checks++;
            boolean stale=false;try{own.reset(0);}catch(IOException expected){stale=true;}check(stale&&own.snapshot().profile().revision==1,"Late stale editor reset overwrote the latest avatar");checks++;
            Bitmap alpha=Bitmap.createBitmap(16,16,Bitmap.Config.ARGB_8888);alpha.eraseColor(0x804080c0);
            try{Bitmap decoded=AvatarImages.preview(AvatarImages.encodeCrop(alpha,new RectF(0,0,16,16)));try{check(Color.alpha(decoded.getPixel(128,128))==128,"Avatar re-encoding flattened transparency");checks++;}finally{decoded.recycle();}}finally{alpha.recycle();}
            Bitmap noisy=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888);int[] pixels=new int[256*256];new Random(27).ints().limit(pixels.length).forEachOrdered(new java.util.function.IntConsumer(){int position;public void accept(int value){pixels[position++]=value;}});noisy.setPixels(pixels,0,256,0,0,256,256);
            try{byte[] reduced=AvatarImages.encodeCrop(noisy,new RectF(0,0,256,256));int edge=AvatarSpec.validatePng(reduced).width();check(edge==192||edge==128,"Noisy image did not use deterministic smaller PNG fallback");check(reduced.length<=AvatarSpec.MAX_BYTES,"PNG fallback exceeded upload bound");checks+=2;}finally{noisy.recycle();}
            Bitmap large=Bitmap.createBitmap(2051,1027,Bitmap.Config.ARGB_8888);large.eraseColor(Color.GREEN);ByteArrayOutputStream largeBytes=new ByteArrayOutputStream();large.compress(Bitmap.CompressFormat.PNG,100,largeBytes);large.recycle();Bitmap bounded=AvatarImages.decode(largeBytes.toByteArray());
            try{check(Math.max(bounded.getWidth(),bounded.getHeight())<=AvatarSpec.MAX_DECODE_EDGE,"Odd source dimensions exceeded decoded pixel bound");checks++;}finally{bounded.recycle();}
            checks+=orientations();
            Bitmap firstFrame=AvatarImages.decode(Base64.getDecoder().decode(GIF));try{check(firstFrame.getPixel(0,0)==Color.RED,"Animated selection did not use static first frame");byte[] png=AvatarImages.encodeCrop(firstFrame,new RectF(1,0,3,2));AvatarSpec.validatePng(png);check(!contains(png,"acTL")&&!contains(png,"fcTL"),"Animation survived avatar re-encoding");checks+=2;}finally{firstFrame.recycle();}
            rejected(()->AvatarImages.decode(new byte[AvatarSpec.MAX_INPUT_BYTES+1]),"Oversized compressed source accepted");checks++;
            rejected(()->AvatarImages.encodeCrop(original,new RectF(0,0,320,160)),"Non-square crop accepted");checks++;
            rejected(()->AvatarImages.decode(context.getContentResolver(),Uri.parse("file:///private/image.png")),"Ungrantable file source accepted");checks++;
            rejected(()->AvatarImages.decode(new byte[]{1,2,3}),"Malformed image accepted");checks++;
            Thread.currentThread().interrupt();try{rejected(()->AvatarImages.decode(encoded),"Canceled image decode continued");checks++;}finally{Thread.interrupted();}
        }finally{original.recycle();try(var entries=Files.walk(directory)){for(Path path:entries.sorted(Comparator.reverseOrder()).collect(java.util.stream.Collectors.toList()))Files.deleteIfExists(path);}}
        return checks;
    }
    private static int orientations()throws Exception {
        Bitmap pixels=Bitmap.createBitmap(2,3,Bitmap.Config.ARGB_8888);int[] colors={Color.RED,Color.GREEN,Color.BLUE,Color.YELLOW,Color.MAGENTA,Color.CYAN};pixels.setPixels(colors,0,2,0,0,2,3);ByteArrayOutputStream bytes=new ByteArrayOutputStream();pixels.compress(Bitmap.CompressFormat.PNG,100,bytes);pixels.recycle();byte[] png=bytes.toByteArray();int checks=0;
        for(int direction=1;direction<=8;direction++){
            byte[] tiff={73,73,42,0,8,0,0,0,1,0,18,1,3,0,1,0,0,0,(byte)direction,0,0,0,0,0,0,0};ByteArrayOutputStream annotated=new ByteArrayOutputStream();DataOutputStream output=new DataOutputStream(annotated);output.write(png,0,33);output.writeInt(tiff.length);output.writeBytes("eXIf");output.write(tiff);java.util.zip.CRC32 crc=new java.util.zip.CRC32();crc.update(new byte[]{101,88,73,102});crc.update(tiff);output.writeInt((int)crc.getValue());output.write(png,33,png.length-33);
            Bitmap decoded=AvatarImages.decode(annotated.toByteArray());
            try {
                check(decoded.getWidth()==(direction>=5?3:2)&&decoded.getHeight()==(direction>=5?2:3),"Orientation dimensions lost: "+direction);checks++;
                for(int y=0;y<3;y++)for(int x=0;x<2;x++){
                    int dx=x,dy=y;switch(direction){case 2:dx=1-x;break;case 3:dx=1-x;dy=2-y;break;case 4:dy=2-y;break;case 5:dx=y;dy=x;break;case 6:dx=2-y;dy=x;break;case 7:dx=2-y;dy=1-x;break;case 8:dx=y;dy=1-x;break;}
                    check(decoded.getPixel(dx,dy)==colors[y*2+x],"Orientation pixel lost: "+direction);checks++;
                }
                byte[] reencoded=AvatarImages.encodeCrop(decoded,new RectF(0,0,2,2));check(!contains(reencoded,"eXIf"),"Selected image metadata survived avatar PNG");checks++;
            } finally { decoded.recycle(); }
        }return checks;
    }
    public static int run(Instrumentation instrumentation,MainActivity main)throws Exception {
        int checks=run(instrumentation.getTargetContext());Context context=instrumentation.getTargetContext();ChatController controller=onMain(instrumentation,()->field(main,"controller"));boolean connected=onMain(instrumentation,()->controller!=null&&controller.connected);
        Identicon.Model corner=Identicon.fromPublicKey(identity());
        for(int i=0;!corner.filled(0,0)&&i<40;i++)corner=Identicon.fromPublicKey(identity());
        check(corner.filled(0,0),"Corner tile fixture could not be generated");
        final Identicon.Model tile=corner;
        for(int edge:new int[]{32,48,96}){
            int actual=onMain(instrumentation,()->{AvatarView view=new AvatarView(context);view.display(tile,null);view.layout(0,0,edge,edge);Bitmap pixels=Bitmap.createBitmap(edge,edge,Bitmap.Config.ARGB_8888);try{view.draw(new Canvas(pixels));int at=(int)Math.ceil(edge/9.0);return pixels.getPixel(at,at);}finally{pixels.recycle();}});
            boolean dark=(context.getResources().getConfiguration().uiMode&android.content.res.Configuration.UI_MODE_NIGHT_MASK)==android.content.res.Configuration.UI_MODE_NIGHT_YES;
            check(actual==tile.foreground(dark),"Default avatar clipped a corner grid cell at "+edge+"px");checks++;
        }
        String localRoot=AndroidIdentity.load().publicKey();AvatarStore store=new AvatarStore(context.getFilesDir().toPath().resolve("avatars"),localRoot);AvatarSnapshot baseline=store.snapshot();
        Activity editor=null;
        try {
            editor=instrumentation.startActivitySync(new Intent(context,AvatarActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Activity current=editor;
            await(()->onMain(instrumentation,()->(boolean)field(current,"ready")),"Avatar editor did not load identity on background thread");checks++;
            Uri slow=Uri.parse("content://dev.ghost.nearbyim.test.avatars/slow/red"),fast=Uri.parse("content://dev.ghost.nearbyim.test.avatars/fast/blue");context.getContentResolver().call(slow,"hold",null,null);
            onMain(instrumentation,()->{invoke(current,"onActivityResult",new Class<?>[]{int.class,int.class,Intent.class},1,Activity.RESULT_OK,new Intent().setData(slow));return null;});
            onMain(instrumentation,()->{invoke(current,"onActivityResult",new Class<?>[]{int.class,int.class,Intent.class},1,Activity.RESULT_OK,new Intent().setData(fast));return null;});context.getContentResolver().call(slow,"release",null,null);
            await(()->onMain(instrumentation,()->{Bitmap source=field(current,"source");return source!=null&&source.getPixel(0,0)==Color.BLUE;}),"Late first selection replaced the newer image");checks++;
            onMain(instrumentation,()->{invoke(current,"onActivityResult",new Class<?>[]{int.class,int.class,Intent.class},1,Activity.RESULT_OK,new Intent().setData(Uri.parse("content://dev.ghost.nearbyim.test.sources/denied")));return null;});
            await(()->onMain(instrumentation,()->{TextView notice=field(current,"notice");return notice.getText().toString().equals(AndroidText.get(current,"avatarUnavailable"));}),"Denied avatar source did not show a recoverable error");check(onMain(instrumentation,()->!current.isFinishing()&&!current.isDestroyed())&&(!connected||controller.connected),"Avatar source failure closed its editor or chat");checks++;
            onMain(instrumentation,()->{invoke(current,"onActivityResult",new Class<?>[]{int.class,int.class,Intent.class},1,Activity.RESULT_OK,new Intent().setData(fast));return null;});await(()->onMain(instrumentation,()->field(current,"source")!=null),"Image selection did not recover after failed avatar source");checks++;
            onMain(instrumentation,()->{AvatarCropView crop=field(current,"crop");RectF before=crop.crop();crop.zoom(2);check(crop.crop().width()<before.width(),"Accessible zoom controls did not change crop");crop.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT,new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_RIGHT));check(crop.crop().centerX()>before.centerX(),"Keyboard crop move did not work");crop.reset();check(Math.abs(crop.crop().width()-before.width())<.02f,"Crop reset did not restore full square");invoke(current,"prepare",new Class<?>[0]);return null;});checks+=3;
            await(()->onMain(instrumentation,()->field(current,"finalPng")!=null),"Avatar PNG preview did not finish");
            byte[] finalPng=onMain(instrumentation,()->field(current,"finalPng"));Bitmap finalImage=onMain(instrumentation,()->field(current,"finalImage"));Bitmap exact=AvatarImages.preview(finalPng);try{check(exact.sameAs(finalImage),"Displayed preview differs from exact final PNG bytes");checks++;}finally{exact.recycle();}
            check(store.snapshot().profile().equals(baseline.profile()),"Choosing or cropping persisted an avatar before Save");checks++;
            onMain(instrumentation,()->{invoke(current,"cancel",new Class<?>[0]);return null;});editor=null;check(store.snapshot().profile().equals(baseline.profile()),"Cancel changed avatar metadata");checks++;
            if(connected)check(controller.connected,"Avatar editor interrupted the established chat session");checks++;
            editor=instrumentation.startActivitySync(new Intent(context,AvatarActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Activity backing=editor;
            await(()->onMain(instrumentation,()->(boolean)field(backing,"ready")),"Back cancellation editor did not initialize");context.getContentResolver().call(slow,"hold",null,null);
            onMain(instrumentation,()->{invoke(backing,"onActivityResult",new Class<?>[]{int.class,int.class,Intent.class},1,Activity.RESULT_OK,new Intent().setData(slow));return null;});int backGeneration=onMain(instrumentation,()->field(backing,"generation"));
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);await(()->onMain(instrumentation,backing::isFinishing),"System Back did not cancel the avatar editor");checks++;
            check(onMain(instrumentation,()->(int)field(backing,"generation")>backGeneration),"System Back did not invalidate the pending avatar worker");checks++;context.getContentResolver().call(slow,"release",null,null);editor=null;
            check(store.snapshot().profile().equals(baseline.profile())&&(!connected||controller.connected),"System Back changed the saved avatar or ended chat");checks++;
            editor=instrumentation.startActivitySync(new Intent(context,AvatarActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Activity saving=editor;
            await(()->onMain(instrumentation,()->(boolean)field(saving,"ready")),"Save editor did not initialize");
            onMain(instrumentation,()->{invoke(saving,"onActivityResult",new Class<?>[]{int.class,int.class,Intent.class},1,Activity.RESULT_OK,new Intent().setData(fast));return null;});
            await(()->onMain(instrumentation,()->field(saving,"source")!=null),"Save editor did not decode selection");onMain(instrumentation,()->{invoke(saving,"prepare",new Class<?>[0]);return null;});
            await(()->onMain(instrumentation,()->field(saving,"finalPng")!=null),"Save editor did not produce exact preview");byte[] expected=onMain(instrumentation,()->field(saving,"finalPng"));
            onMain(instrumentation,()->{invoke(saving,"save",new Class<?>[0]);return null;});await(()->onMain(instrumentation,saving::isFinishing),"Save did not finish after successful transaction");editor=null;
            check(Arrays.equals(store.snapshot().png(),expected),"Save button did not persist its exact displayed PNG");checks++;
            editor=instrumentation.startActivitySync(new Intent(context,AvatarActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Activity resetting=editor;
            await(()->onMain(instrumentation,()->(boolean)field(resetting,"ready")),"Reset editor did not initialize");onMain(instrumentation,()->{invoke(resetting,"resetDefault",new Class<?>[0]);return null;});await(()->onMain(instrumentation,resetting::isFinishing),"Default reset did not finish after successful transaction");editor=null;
            check(store.snapshot().profile().avatarHash.isEmpty()&&store.snapshot().png().length==0,"Default avatar button retained custom pixels");checks++;
            if(connected)check(controller.connected,"Saving/resetting an avatar interrupted chat");checks++;
            checks+=manager(instrumentation,context);
        }finally{context.getContentResolver().call(Uri.parse("content://dev.ghost.nearbyim.test.avatars/slow/red"),"release",null,null);if(editor!=null){Activity closing=editor;onMain(instrumentation,()->{closing.finish();return null;});}AvatarSnapshot latest=store.snapshot();if(!latest.profile().avatarHash.equals(baseline.profile().avatarHash)){if(baseline.png().length==0)store.reset(latest.profile().revision);else store.save(latest.profile().revision,baseline.png());AndroidAvatars.get(context).committed(localRoot);}}
        return checks;
    }
    private static int manager(Instrumentation instrumentation,Context context)throws Exception {
        AndroidAvatars manager=AndroidAvatars.get(context);List<String> roots=new ArrayList<>();List<AvatarView> views=new ArrayList<>();Bitmap pixels=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888);pixels.eraseColor(Color.GREEN);byte[] png=AvatarImages.encodeCrop(pixels,new RectF(0,0,256,256));pixels.recycle();
        for(int i=0;i<10;i++){String root=identity();roots.add(root);new AvatarStore(context.getFilesDir().toPath().resolve("avatars"),root).save(0,png);onMain(instrumentation,()->{AvatarView view=new AvatarView(context);views.add(view);manager.bind(view,root);return null;});}
        await(()->onMain(instrumentation,()->((Set<?>)field(manager,"loading")).isEmpty()),"Bound avatar cache did not settle");check(onMain(instrumentation,()->(int)field(manager,"cachedBytes")<=AvatarSpec.MAX_CACHE_BYTES),"Avatar bitmap cache exceeded 2 MiB");
        String root=roots.get(roots.size()-1);AvatarView own=views.get(views.size()-1);await(()->onMain(instrumentation,()->field(own,"image")!=null),"Identity-bound custom avatar was not displayed");
        new AvatarStore(context.getFilesDir().toPath().resolve("avatars"),roots.get(0)).reset(1);manager.refresh(roots.get(0));await(()->onMain(instrumentation,()->((Set<?>)field(manager,"loading")).isEmpty()),"Root refresh did not settle");check(onMain(instrumentation,()->{Bitmap retained=field(own,"image");return retained!=null&&retained.getPixel(128,128)==Color.GREEN;}),"Refreshing another root changed the device's avatar view");
        for(String identity:roots){AvatarStore store=new AvatarStore(context.getFilesDir().toPath().resolve("avatars"),identity);store.reset(store.snapshot().profile().revision);manager.refresh(identity);}return 4;
    }
    private static boolean contains(byte[] data,String text){return new String(data,java.nio.charset.StandardCharsets.ISO_8859_1).contains(text);}
    private interface Checked {void run()throws Exception;}
    private static void rejected(Checked action,String message)throws Exception{try{action.run();}catch(IOException expected){return;}throw new AssertionError(message);}
    private static String identity()throws Exception{KeyPairGenerator generator=KeyPairGenerator.getInstance("EC");generator.initialize(new ECGenParameterSpec("secp256r1"));return new DeviceIdentity(generator.generateKeyPair()).publicKey();}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static void await(Callable<Boolean> condition,String message)throws Exception{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);while(System.nanoTime()<deadline){if(condition.call())return;Thread.sleep(20);}throw new AssertionError(message);}
    private static <T>T onMain(Instrumentation instrumentation,Callable<T> operation)throws Exception{AtomicReference<T> result=new AtomicReference<>();AtomicReference<Throwable> failure=new AtomicReference<>();instrumentation.runOnMainSync(()->{try{result.set(operation.call());}catch(Throwable error){failure.set(error);}});if(failure.get()!=null)throw new Exception("Native avatar main-thread operation",failure.get());return result.get();}
    @SuppressWarnings("unchecked") private static <T>T field(Object target,String name)throws Exception{Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return(T)field.get(target);}
    private static Object invoke(Object target,String name,Class<?>[] types,Object...arguments)throws Exception{Method method=target.getClass().getDeclaredMethod(name,types);method.setAccessible(true);return method.invoke(target,arguments);}
}
