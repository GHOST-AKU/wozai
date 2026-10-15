package dev.ghost.nearbyim;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.os.*;
import android.view.*;
import android.widget.*;
import dev.ghost.nearbyim.core.*;
import dev.ghost.nearbyim.i18n.UiText;
import dev.ghost.nearbyim.transport.*;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Narrow native suite on a disposable tablet emulator; synthetic hinges do not validate OEM hardware. */
public final class AdaptiveInstrumentation extends Instrumentation {
    private final AtomicReference<MainActivity> latest=new AtomicReference<>();
    private final CountDownLatch dataBlocked=new CountDownLatch(1),releaseData=new CountDownLatch(1);
    private final ThreadPoolExecutor remoteQueue=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(96),task->{Thread thread=new Thread(task,"adaptive-fixture-receive");thread.setDaemon(true);return thread;},new ThreadPoolExecutor.AbortPolicy());
    private final BlockingQueue<AttachmentRecord> remoteRecords=new LinkedBlockingQueue<>();
    private final AtomicInteger remoteTexts=new AtomicInteger();
    private AttachmentTransferV2 remoteFiles;
    private int checks;private ChatController controller;private FramedSession remote;private MainActivity activity;
    private final Application.ActivityLifecycleCallbacks lifecycle=new Application.ActivityLifecycleCallbacks(){
        public void onActivityResumed(Activity a){if(a instanceof MainActivity m)latest.set(m);}
        public void onActivityCreated(Activity a,Bundle b){}public void onActivityStarted(Activity a){}public void onActivityPaused(Activity a){}public void onActivityStopped(Activity a){}public void onActivitySaveInstanceState(Activity a,Bundle b){}public void onActivityDestroyed(Activity a){}
    };
    public void onCreate(Bundle arguments){super.onCreate(arguments);start();}
    public void onStart(){
        Bundle results=new Bundle();int result=Activity.RESULT_OK;Application app=(Application)getTargetContext().getApplicationContext();app.registerActivityLifecycleCallbacks(lifecycle);
        try{
            activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            await(()->onMain(()->field(activity,"controller")!=null),"Controller attached");controller=onMain(()->field(activity,"controller"));
            nativeLayouts();progress("Native layout geometry completed");liveContinuity();progress("Live file/window continuity completed");discoveryDeadline();progress("Five-second discovery deadline completed");
            results.putString("stream","Adaptive Android: "+checks+" checks passed\n");
        }catch(Throwable failure){result=Activity.RESULT_CANCELED;StringWriter trace=new StringWriter();failure.printStackTrace(new PrintWriter(trace));results.putString("stream",trace.toString());}
        finally{
            shell("wm size reset");shell("wm density reset");shell("settings put system font_scale 1.0");
            releaseData.countDown();if(remote!=null)remote.close(UiText.of("connectionEnded"));
            remoteQueue.shutdown();try{remoteQueue.awaitTermination(2,TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
            if(remoteFiles!=null)try{remoteFiles.shutdown().get(10,TimeUnit.SECONDS);}catch(Exception ignored){}
            try{onMain(()->{if(controller!=null)controller.stopAll();MainActivity m=latest.get();if(m!=null)m.finish();return null;});}catch(Exception ignored){}
            app.unregisterActivityLifecycleCallbacks(lifecycle);
        }finish(result,results);
    }
    private void nativeLayouts()throws Exception {
        onMain(()->{
            AdaptiveScaffold root=field(activity,"root");View home=field(activity,"homePage"),chat=field(activity,"chatPage");LinearLayout nav=field(activity,"bottomNav");
            String peer=UUID.randomUUID().toString();controller.selectConversation(new ChatStore.Conversation(peer,"Tablet peer",0));set(activity,"detail",true);invoke(activity,"render");
            EditText draft=field(activity,"composer");draft.setText("fold draft 中文");draft.setSelection(2,5);Object sameDraft=draft;
            for(int width:new int[]{360,599,600,839,840,1280}){
                root.fold(null);measure(root,width,800);
                check(root.dualPane()==(width>=840),"Actual panes follow "+width+"dp breakpoint");
                check(home.getVisibility()==(width>=840?View.VISIBLE:View.GONE)&&chat.getVisibility()==View.VISIBLE,"List/chat visibility follows actual available space");
                check(width>=600?nav.getVisibility()==View.VISIBLE&&nav.getOrientation()==LinearLayout.VERTICAL:nav.getVisibility()==View.GONE,"Detail navigation adapts without rebuilding controls");
                check(draft==sameDraft&&draft.getText().toString().equals("fold draft 中文")&&draft.getSelectionStart()==2&&draft.getSelectionEnd()==5,"Resize preserves composer and selected text");
                if(width>=840)check(home.getRight()<=chat.getLeft(),"Actual child bounds do not overlap");
            }
            int unit=dp(1);root.fold(new AdaptiveLayout.Fold(dp(480),0,dp(520),dp(800),true,true,true));measure(root,1000,800);
            check(home.getRight()<=dp(480)&&chat.getLeft()>=dp(520),"Native views avoid the simulated hinge");
            root.fold(new AdaptiveLayout.Fold(0,dp(400),dp(1000),dp(440),false,true,false));measure(root,1000,900);
            check(!root.dualPane()&&chat.getTop()>=dp(440),"Tabletop avoids the separating horizontal fold");
            root.fold(null);measure(root,1280,400);check(!root.dualPane(),"Short window falls back without losing current chat");
            check(draft==sameDraft&&draft.getText().toString().equals("fold draft 中文"),"Posture fallback preserves input");
            root.setPadding(0,0,0,dp(350));measure(root,1000,800);check(!root.dualPane()&&chat.getBottom()<=dp(450),"IME insets constrain the usable chat area");root.setPadding(0,0,0,0);
            root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);measure(root,1000,800);check(chat.getRight()<=home.getLeft()&&nav.getLeft()>=home.getRight(),"RTL mirrors the real pane geometry");root.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
            root.fold(null);root.requestApplyInsets();root.requestLayout();return null;
        });
        waitForIdleSync();screenshot("tablet-layout.png");
    }
    private void liveContinuity()throws Exception {
        onMain(()->{controller.start(Peer.LAN);return null;});await(()->onMain(()->controller.lanListening),"LAN reception starts");
        CountDownLatch ready=new CountDownLatch(1);String peer=UUID.randomUUID().toString();AtomicReference<FramedSession> endpoint=new AtomicReference<>();
        DeviceIdentity identity=DeviceIdentity.generate();byte[] key=new byte[32];new java.security.SecureRandom().nextBytes(key);
        try(ServerSocket server=new ServerSocket(0,1,InetAddress.getLoopbackAddress())){
            Socket outgoing=new Socket(InetAddress.getLoopbackAddress(),server.getLocalPort()),incoming=server.accept();
            remote=FramedSession.secure(stream(outgoing),peer,"Resize peer",identity,key,true,new FramedSession.Listener(){
                public void onHello(Frame hello){try{FramedSession wire=endpoint.get();remoteFiles=new AttachmentTransferV2(new File(getTargetContext().getCacheDir(),"adaptive-receiver-"+peer).toPath(),identity.fingerprint(),DeviceIdentity.fingerprint(wire.remotePublicKey()),wire.connectionGeneration(),false,new AttachmentTransferV2.Wire(){public boolean send(TransferPacket packet){return wire.sendTransfer(packet);}public void abort(){wire.close(UiText.of("attachmentFailed"));}},remoteRecords::add);wire.approve();}catch(Exception failure){throw new IllegalStateException(failure);}}public void onReady(){ready.countDown();}
                public void onTransfer(TransferPacket packet){remoteQueue.execute(()->{try{if(packet.kind==TransferPacket.Kind.DATA&&packet.offset>=256*1024&&dataBlocked.getCount()>0){dataBlocked.countDown();if(!releaseData.await(90,TimeUnit.SECONDS))throw new IOException("Native resize gate timed out");}remoteFiles.receive(packet,true);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}catch(IOException error){throw new UncheckedIOException(error);}});}
                public void onClosed(UiText reason){}
                public void onText(Frame text){remoteTexts.incrementAndGet();endpoint.get().acknowledge(text.id);}public void onAck(String id){}
            });endpoint.set(remote);remote.start();
            onMain(()->{controller.onConnection(Peer.LAN,stream(incoming),true);return null;});
        }
        await(()->onMain(()->controller.approvalId!=null),"Real encrypted peer awaits approval");
        onMain(()->{controller.approve(controller.approvalId);controller.selectConversation(new ChatStore.Conversation(peer,"Resize peer",0));set(activity,"detail",true);invoke(activity,"render");EditText draft=field(activity,"composer");draft.setText("Unsent across recreation");draft.setSelection(3,8);return null;});
        check(ready.await(10,TimeUnit.SECONDS),"Noise peer ready");await(()->onMain(()->controller.connected),"Controller connected");
        for(int i=0;i<24;i++)check(remote.send(new Frame(Frame.TEXT,UUID.randomUUID().toString(),"Read anchor "+i+" long content to exercise wrapping and preserve the selected message across changing pane widths. 中文阅读位置测试。\nAnother line\nThird line",System.currentTimeMillis())),"Timeline fixture text sent");
        await(()->onMain(()->controller.messages.size()>=24),"Native timeline loaded");
        Object wire=field(controller,"active");ClientAttachmentTransfers files=field(controller,"transfers");AutomaticFileRoutes routes=field(controller,"fileRoutes");routes.ensureReady().get(5,TimeUnit.SECONDS);
        Path source=new File(getTargetContext().getCacheDir(),"adaptive-source.bin").toPath();byte[] bytes=new byte[2*1024*1024];new java.util.Random(25).nextBytes(bytes);Files.write(source,bytes);
        String expectedHash=hex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        files.offer(new FileAttachmentSource(source),"adaptive-source.bin","application/octet-stream").get(10,TimeUnit.SECONDS);check(dataBlocked.await(10,TimeUnit.SECONDS),"Real encrypted file is in flight before resizing");
        waitForIdleSync();onMain(()->{((ScrollView)field(activity,"messageScroll")).scrollTo(0,dp(500));return null;});
        String anchor=onMain(()->readingAnchor(activity));check(anchor!=null,"Reading anchor available before recreation");
        MainActivity old=activity;
        onMain(()->{old.recreate();return null;});await(()->latest.get()!=null&&latest.get()!=old,"Activity recreated");activity=latest.get();
        await(()->onMain(()->field(activity,"controller")==controller),"Started foreground service/controller survives recreation");
        await(()->onMain(()->anchor.equals(readingAnchor(activity))),"Reading message survives recreation");
        onMain(()->{EditText draft=field(activity,"composer");check(draft.getText().toString().equals("Unsent across recreation")&&draft.getSelectionStart()==3&&draft.getSelectionEnd()==8,"Draft and caret survive native recreation");check(field(controller,"active")==wire&&field(controller,"transfers")==files&&controller.connected,"UI recreation keeps the same Noise session");return null;});
        shell("wm size 900x1600");shell("wm density 320");
        await(()->onMain(()->{MainActivity m=latest.get();return m!=null&&field(m,"controller")==controller&&!((AdaptiveScaffold)field(m,"root")).dualPane();}),"Actual window change becomes compact while retaining the live session");activity=latest.get();
        await(()->onMain(()->anchor.equals(readingAnchor(latest.get()))),"Real window resize retains reading message");
        check(controller.connected&&field(controller,"active")==wire,"Real resizing doesn't reconnect Noise");screenshot("compact-window.png");
        shell("wm size reset");shell("wm density reset");await(()->onMain(()->{MainActivity m=latest.get();return m!=null&&field(m,"controller")==controller&&((AdaptiveScaffold)field(m,"root")).dualPane();}),"Restored tablet window shows two panes again");activity=latest.get();
        await(()->onMain(()->anchor.equals(readingAnchor(latest.get()))),"Restored tablet retains reading message");
        onMain(()->{check(((EditText)field(activity,"composer")).getText().toString().equals("Unsent across recreation"),"Real window transitions preserve draft");check(field(controller,"transfers")==files,"Window resizing retains the same file engine");return null;});screenshot("restored-tablet.png");
        MainActivity beforeFont=activity;shell("settings put system font_scale 2.0");
        await(()->onMain(()->{MainActivity m=latest.get();return m!=null&&m!=beforeFont&&field(m,"controller")==controller&&m.getResources().getConfiguration().fontScale>1.9f;}),"Large font recreates UI without replacing the transfer controller");activity=latest.get();
        onMain(()->{check(((EditText)field(activity,"composer")).getText().toString().equals("Unsent across recreation"),"Large font preserves draft");android.graphics.Rect bounds=new android.graphics.Rect();View send=field(activity,"sendButton");check(send.getGlobalVisibleRect(bounds)&&bounds.height()>=dp(48),"Large font leaves the send action accessible");return null;});screenshot("large-font.png");
        releaseData.countDown();AttachmentRecord received=null;long deadline=SystemClock.elapsedRealtime()+15000;
        while(SystemClock.elapsedRealtime()<deadline){AttachmentRecord record=remoteRecords.poll(1,TimeUnit.SECONDS);if(record!=null&&record.state.equals("received")){received=record;break;}}
        check(received!=null&&received.info.size==bytes.length&&received.info.hash.equals(expectedHash),"File finishes with original length/hash after recreation and real resize");
        await(()->onMain(()->controller.messages.stream().anyMatch(m->m.attachment!=null&&m.attachment.state.equals("delivered"))),"Sender retains durable delivered receipt");
        onMain(()->{EditText composer=field(activity,"composer");composer.setText("IME composition");android.view.inputmethod.BaseInputConnection.setComposingSpans(composer.getText());composer.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_ENTER));android.view.inputmethod.BaseInputConnection.removeComposingSpans(composer.getText());composer.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_ENTER));return null;});Thread.sleep(300);check(remoteTexts.get()==0,"IME commit does not accidentally send on key release");
        onMain(()->{EditText composer=field(activity,"composer");composer.setText("Committed keyboard message");composer.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_ENTER));composer.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_ENTER));return null;});await(()->remoteTexts.get()==1,"Hardware Enter sends one encrypted text");
    }
    private void discoveryDeadline()throws Exception {
        AtomicReference<AndroidFilePlatform> platform=new AtomicReference<>();CountDownLatch failed=new CountDownLatch(1);AtomicLong elapsed=new AtomicLong();long start=SystemClock.elapsedRealtime();
        onMain(()->{
            AndroidFilePlatform p=new AndroidFilePlatform(getTargetContext(),UUID.randomUUID().toString(),"Test",UUID.randomUUID().toString());platform.set(p);
            set(p,"directFailed",(java.util.function.Consumer<Throwable>)e->{elapsed.set(SystemClock.elapsedRealtime()-start);failed.countDown();});set(p,"directOpened",(java.util.function.Consumer<AutomaticFileRoutes.Opened>)s->{});
            Object transport=field(p,"direct");TransportListener listener=field(transport,"listener");listener.onListening(Peer.WIFI_DIRECT,UiText.of("directAdvertising"));
            new Handler(Looper.getMainLooper()).postDelayed(()->listener.onListening(Peer.WIFI_DIRECT,UiText.of("directAdvertising")),2000);return null;
        });
        try{check(failed.await(7,TimeUnit.SECONDS),"Five-second discovery deadline completes");check(elapsed.get()>=4900&&elapsed.get()<6500,"Duplicate advertising does not extend the five-second deadline: "+elapsed.get());}
        finally{onMain(()->{platform.get().close();return null;});}
    }
    private static String readingAnchor(MainActivity activity)throws Exception{Method method=MainActivity.class.getDeclaredMethod("captureTimelinePosition");method.setAccessible(true);Object position=method.invoke(activity);return field(position,"anchor");}
    private static String hex(byte[] values){StringBuilder out=new StringBuilder();for(byte value:values)out.append(String.format(java.util.Locale.ROOT,"%02x",value&255));return out.toString();}
    private void progress(String text){Bundle status=new Bundle();status.putString("stream",text+"\n");sendStatus(0,status);}
    private int dp(int n){return Math.round(n*getTargetContext().getResources().getDisplayMetrics().density);}
    private void measure(View root,int w,int h){root.measure(View.MeasureSpec.makeMeasureSpec(dp(w),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(dp(h),View.MeasureSpec.EXACTLY));root.layout(0,0,dp(w),dp(h));}
    private void screenshot(String name)throws Exception {waitForIdleSync();Bitmap shot=getUiAutomation().takeScreenshot();if(shot==null)return;File dir=new File(getTargetContext().getExternalFilesDir(null),"adaptive");dir.mkdirs();try(var out=new FileOutputStream(new File(dir,name))){shot.compress(Bitmap.CompressFormat.PNG,100,out);}finally{shot.recycle();}}
    private void shell(String command){try(var fd=getUiAutomation().executeShellCommand(command);var in=new FileInputStream(fd.getFileDescriptor())){in.transferTo(OutputStream.nullOutputStream());}catch(Exception ignored){}}
    private <T>T onMain(Callable<T> task)throws Exception {Object[] result=new Object[2];runOnMainSync(()->{try{result[0]=task.call();}catch(Throwable error){result[1]=error;}});if(result[1]!=null)throw new Exception((Throwable)result[1]);@SuppressWarnings("unchecked")T value=(T)result[0];return value;}
    private void await(Callable<Boolean> test,String message)throws Exception {long end=SystemClock.elapsedRealtime()+15000;while(SystemClock.elapsedRealtime()<end){if(test.call()){checks++;return;}Thread.sleep(40);}throw new AssertionError(message);}
    private void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
    @SuppressWarnings("unchecked")private static <T>T field(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return (T)f.get(o);}
    private static void set(Object o,String name,Object value)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value);}
    private static void invoke(Object o,String name)throws Exception{Method m=o.getClass().getDeclaredMethod(name);m.setAccessible(true);m.invoke(o);}
    private static StreamConnection stream(Socket socket){return new StreamConnection(){public InputStream input()throws IOException{return socket.getInputStream();}public OutputStream output()throws IOException{return socket.getOutputStream();}public String label(){return "adaptive loopback";}public void close()throws IOException{socket.close();}};}
}
