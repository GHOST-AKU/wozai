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
            nativeLayouts();progress("Native layout geometry completed");checks+=AndroidWifiDirectUiChecks.fileDiscoveryFailure(this);checks+=AndroidKnownGroupChecks.run(this);discoveryDeadline();progress("Known group builders/wait lifecycle and prepared scan deadline verified");liveContinuity();progress("Live file/window continuity completed");appearanceChoices();progress("Native theme overrides and recreation continuity completed");
            checks+=AndroidReceiveDirectoryChecks.run(getTargetContext());receiveDirectoryUi();progress("Native receive-folder grants, copies, restart, deletion and real Noise receipt checks completed");
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
    private void receiveDirectoryUi()throws Exception {
        Context context=getTargetContext();AttachmentLocations locations=AttachmentLocations.get(context);AttachmentLocations.Directory original=locations.directory();
        AndroidReceiveDirectoryChecks.command(context,"directory-grant","two");Object service=onMain(()->field(activity,"service"));UriHolder destination=new UriHolder();String peer=onMain(()->controller.selectedId);
        try{
            onMain(()->{activity.onActivityResult(26,Activity.RESULT_OK,new Intent().setData(AndroidReceiveDirectoryChecks.tree("two")).addFlags(AndroidReceiveDirectoryChecks.FLAGS));return null;});
            await(()->locations.directory()!=null&&locations.directory().uri().equals(AndroidReceiveDirectoryChecks.tree("two")),"Actual picker callback persists the chosen receive directory");
            onMain(()->{invoke(activity,"renderSettings");check(((TextView)field(activity,"receiveFolderValue")).getText().toString().equals("two"),"Settings show the selected folder name");activity.onActivityResult(26,Activity.RESULT_CANCELED,null);return null;});
            check(locations.directory().uri().equals(AndroidReceiveDirectoryChecks.tree("two")),"Canceling folder selection preserves the existing directory");
            MainActivity previous=activity;onMain(()->{previous.recreate();return null;});await(()->latest.get()!=null&&latest.get()!=previous&&onMain(()->field(latest.get(),"controller")==controller),"Folder selection survives real Activity recreation");activity=latest.get();
            check(onMain(()->field(activity,"service")==service&&controller.connected),"Folder selection and recreation preserve the live service and Noise connection");
            Path source=context.getCacheDir().toPath().resolve("receive-folder-noise.bin");byte[] bytes=new byte[32769];new Random(29).nextBytes(bytes);Files.write(source,bytes);
            String id=remoteFiles.offer(new FileAttachmentSource(source),"收到的原名.bin","application/octet-stream").get(10,TimeUnit.SECONDS);
            await(()->onMain(()->controller.messages.stream().anyMatch(message->message.id.equals(id)&&message.attachment!=null&&!message.outgoing&&message.attachment.state.equals("received"))),"Real encrypted incoming file commits its original saved receipt");
            AttachmentRecord record=onMain(()->controller.messages.stream().filter(message->message.id.equals(id)&&!message.outgoing).findFirst().orElseThrow().attachment);
            await(()->locations.savedStatus(peer,record)!=null&&locations.savedStatus(peer,record).state()==AttachmentDirectoryStore.State.SAVED,"Production receipt callback automatically saves to the chosen folder");
            AttachmentLocations.Location location=locations.locate(peer,record).get(10,TimeUnit.SECONDS);destination.uri=location.savedUri();check(location.kind()==AttachmentLocations.Kind.DOCUMENT&&location.availability()==AttachmentLocations.Availability.AVAILABLE,"Real received file advertises its public document location");
            try(InputStream input=context.getContentResolver().openInputStream(destination.uri)){ByteArrayOutputStream output=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int count;while((count=input.read(buffer))!=-1)output.write(buffer,0,count);check(Arrays.equals(bytes,output.toByteArray()),"Noise payload and public folder bytes agree");}
            await(()->onMain(()->{View row=((LinearLayout)field(activity,"bubbles")).findViewWithTag("message:"+id+":false");TextView status=row==null?null:row.findViewWithTag("receiveFolderStatus");return status!=null&&status.getText().toString().equals(AndroidText.get(activity,"receiveFolderSaved"));}),"File bubble shows the successful folder save");
        }finally{
            if(destination.uri!=null)android.provider.DocumentsContract.deleteDocument(context.getContentResolver(),destination.uri);
            if(original==null)locations.stopDirectory();else locations.selectDirectory(original.uri(),AndroidReceiveDirectoryChecks.FLAGS).get(10,TimeUnit.SECONDS);
            locations.clearPeer(peer).get(10,TimeUnit.SECONDS);locations.finishClear(peer);AndroidReceiveDirectoryChecks.command(context,"directory-revoke","two");
        }
    }
    private static final class UriHolder {android.net.Uri uri;}
    private void appearanceChoices()throws Exception {
        checks+=AndroidAppearanceChecks.run(getTargetContext());
        String original=AppAppearance.selection(getTargetContext());
        Object service=onMain(()->field(activity,"service"));
        String draft=onMain(()->((EditText)field(activity,"composer")).getText().toString());
        int start=onMain(()->((EditText)field(activity,"composer")).getSelectionStart()),end=onMain(()->((EditText)field(activity,"composer")).getSelectionEnd());
        boolean connected=onMain(()->controller.connected);
        try{
            for(String choice:new String[]{AppAppearance.LIGHT,AppAppearance.DARK,AppAppearance.SYSTEM}){
                if(!choice.equals(AppAppearance.selection(getTargetContext()))){
                    MainActivity previous=activity;latest.set(null);onMain(()->{invoke(previous,"selectTheme",new Class<?>[]{String.class},choice);return null;});
                    await(()->latest.get()!=null&&latest.get()!=previous,"Theme selection recreates the main activity");activity=latest.get();
                    await(()->onMain(()->field(activity,"controller")==controller),"Theme recreation rebinds the surviving controller");waitForIdleSync();
                }
                int expected=choice.equals(AppAppearance.SYSTEM)?getTargetContext().getResources().getConfiguration().uiMode&android.content.res.Configuration.UI_MODE_NIGHT_MASK:choice.equals(AppAppearance.DARK)?android.content.res.Configuration.UI_MODE_NIGHT_YES:android.content.res.Configuration.UI_MODE_NIGHT_NO;
                check(onMain(()->(activity.getResources().getConfiguration().uiMode&android.content.res.Configuration.UI_MODE_NIGHT_MASK)==expected),"Activity resources use the selected theme");
                check(onMain(()->field(activity,"service")==service&&controller.connected==connected),"Theme change preserves the same service and connection state");
                check(onMain(()->{EditText composer=field(activity,"composer");return composer.getText().toString().equals(draft)&&composer.getSelectionStart()==start&&composer.getSelectionEnd()==end;}),"Theme recreation preserves draft and selection");
                check(onMain(()->((TextView)field(activity,"themeValue")).getText().toString().equals(AndroidText.get(activity,choice.equals(AppAppearance.SYSTEM)?"systemTheme":choice.equals(AppAppearance.DARK)?"darkTheme":"lightTheme"))),"Settings show the persisted appearance choice");
            }
        }finally{AppAppearance.select(getTargetContext(),original);}
    }
    private void nativeLayouts()throws Exception {
        onMain(()->{
            AdaptiveScaffold root=field(activity,"root");View home=field(activity,"homePage"),chat=field(activity,"chatPage");LinearLayout nav=field(activity,"bottomNav");
            String peer=UUID.randomUUID().toString();controller.selectConversation(new ChatStore.Conversation(peer,"Tablet peer",0));set(activity,"detail",true);invoke(activity,"render");
            EditText draft=field(activity,"composer");draft.setText("fold draft 中文");draft.setSelection(2,5);Object sameDraft=draft;
            for(int width:new int[]{360,599,600,727,728,800,839,840,1280}){
                root.fold(null);measure(root,width,800);
                check(root.dualPane()==(width>=728),"Actual panes follow "+width+"dp breakpoint");
                check(home.getVisibility()==(width>=728?View.VISIBLE:View.GONE)&&chat.getVisibility()==View.VISIBLE,"List/chat visibility follows actual available space");
                check(width>=600?nav.getVisibility()==View.VISIBLE&&nav.getOrientation()==LinearLayout.VERTICAL:nav.getVisibility()==View.GONE,"Detail navigation adapts without rebuilding controls");
                check(draft==sameDraft&&draft.getText().toString().equals("fold draft 中文")&&draft.getSelectionStart()==2&&draft.getSelectionEnd()==5,"Resize preserves composer and selected text");
                if(width>=728)check(home.getRight()<=chat.getLeft(),"Actual child bounds do not overlap");
                if(width>=600){
                    LinearLayout[] destinations=field(activity,"navItems");
                    View compose=nav.findViewWithTag("railCompose");
                    View composeButton=((LinearLayout)compose).getChildAt(0);
                    check(composeButton.getLeft()+composeButton.getWidth()/2==compose.getWidth()/2,"New chat button is centered in its rail container");
                    check(composeButton.getPaddingLeft()==(composeButton.getWidth()-dp(24))/2,"New chat glyph is centered inside its 56dp target");
                    check(compose.getVisibility()==View.VISIBLE&&compose.getBottom()<destinations[0].getTop(),"New chat is separated from destinations");
                    check(destinations[0].getBottom()+dp(16)<=destinations[1].getTop()&&destinations[1].getBottom()+dp(16)<=destinations[2].getTop(),"Rail destinations have real space between targets");
                    check(destinations[2].getBottom()<=nav.getHeight()-dp(16),"Settings stays inside the rail bottom inset");
                    View pill=destinations[0].findViewWithTag("pill");destinations[0].setPressed(true);
                    check(android.util.StateSet.stateSetMatches(new int[]{android.R.attr.state_pressed},pill.getDrawableState())&&pill.getForeground() instanceof android.graphics.drawable.RippleDrawable&&destinations[0].getBackground()==null,"Only the small capsule draws press feedback");
                    destinations[0].setPressed(false);
                }
            }
            draggableDivider(root,home,chat,draft);
            set(activity,"detail",false);invoke(activity,"render");measure(root,360,800);
            check(nav.findViewWithTag("railCompose").getVisibility()==View.GONE&&nav.getOrientation()==LinearLayout.HORIZONTAL,"Phone navigation omits rail-only action and gaps");
            set(activity,"detail",true);invoke(activity,"render");
            reconnectGeometry();
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
    private void draggableDivider(AdaptiveScaffold root,View home,View chat,EditText draft)throws Exception {
        root.setPadding(0,0,0,0);measure(root,1280,800);
        int defaultX=dev.ghost.nearbyim.core.AdaptiveLayout.compute(dp(1280),dp(800),root.getResources().getDisplayMetrics().density,root.getResources().getConfiguration().fontScale,false,true,null).list().right();
        View divider=root.findViewWithTag("paneDivider");
        check(divider.getVisibility()==View.VISIBLE&&divider.getWidth()>=dp(48),"Divider has a usable native touch target");
        float x=divider.getLeft()+divider.getWidth()/2f,y=dp(300);long now=SystemClock.uptimeMillis();
        touch(root,now,MotionEvent.ACTION_DOWN,x,y);touch(root,now+20,MotionEvent.ACTION_MOVE,dp(632),y);measure(root,1280,800);
        check(Math.abs(home.getRight()-dp(640))<=1&&chat.getLeft()==home.getRight(),"Native drag snaps the divider to the window center");
        touch(root,now+40,MotionEvent.ACTION_UP,dp(632),y);
        Bundle position=new Bundle();position.putFloat(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,65);
        check(divider.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId(),position),"TalkBack can adjust the split");measure(root,1280,800);
        check(Math.abs(home.getRight()-dp(832))<=1,"Accessibility changes the actual pane width");
        check(Math.abs(getTargetContext().getSharedPreferences("ui",Context.MODE_PRIVATE).getFloat("paneSplit",0)-.65f)<.001f,"Completed adjustment is persisted");
        check(divider.performClick(),"Divider offers a center action");measure(root,1280,800);
        check(home.getRight()==dp(640),"Center action restores an exact centered split");
        check(draft.getText().toString().equals("fold draft 中文")&&draft.getSelectionStart()==2&&draft.getSelectionEnd()==5,"Dragging retains the same live composer and selection");
        touch(root,now+60,MotionEvent.ACTION_DOWN,dp(640),y);touch(root,now+80,MotionEvent.ACTION_MOVE,dp(800),y);measure(root,1280,800);
        touch(root,now+100,MotionEvent.ACTION_CANCEL,dp(800),y);measure(root,1280,800);
        check(home.getRight()==dp(640),"Cancelled drag restores the prior split");
        touch(root,now+120,MotionEvent.ACTION_DOWN,dp(640),y);touch(root,now+140,MotionEvent.ACTION_MOVE,defaultX+dp(8),y);measure(root,1280,800);
        check(home.getRight()==defaultX,"Dragging near the original adaptive position snaps back exactly");
        touch(root,now+160,MotionEvent.ACTION_UP,defaultX+dp(8),y);
        getTargetContext().getSharedPreferences("ui",Context.MODE_PRIVATE).edit().remove("paneSplit").apply();set(root,"splitPosition",Float.NaN);measure(root,1280,800);
    }
    private void touch(View root,long time,int action,float x,float y){
        MotionEvent event=MotionEvent.obtain(time,time,action,x,y,0);try{check(root.dispatchTouchEvent(event),"Divider handles native pointer event "+action);}finally{event.recycle();}
    }
    private void reconnectGeometry(){
        AdaptiveReconnectActions row=new AdaptiveReconnectActions(activity);
        TextView hint=new TextView(activity);hint.setText("Remembered device. The other device needs to enable reception. 已记住设备，对方需要开启接收。");
        Button connect=new Button(activity);connect.setText("Connect 连接");Button nearby=new Button(activity);nearby.setText("Find nearby 去附近查找");
        row.addView(hint);row.addView(connect);row.addView(nearby);
        for(float size:new float[]{13,26})for(int width:new int[]{240,360,480,900})for(int direction:new int[]{View.LAYOUT_DIRECTION_LTR,View.LAYOUT_DIRECTION_RTL}){
            hint.setTextSize(size);connect.setTextSize(size);nearby.setTextSize(size);row.setLayoutDirection(direction);
            row.measure(View.MeasureSpec.makeMeasureSpec(dp(width),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));row.layout(0,0,row.getMeasuredWidth(),row.getMeasuredHeight());
            for(View child:new View[]{hint,connect,nearby})check(child.getLeft()>=0&&child.getRight()<=row.getWidth()&&child.getTop()>=0&&child.getBottom()<=row.getHeight(),"Connection hint/action bounds fit at "+width+"dp and "+size+"sp");
            check(!android.graphics.Rect.intersects(bounds(hint),bounds(connect))&&!android.graphics.Rect.intersects(bounds(connect),bounds(nearby)),"Connection actions never overlap wrapped text or each other");
        }
    }
    private android.graphics.Rect bounds(View view){return new android.graphics.Rect(view.getLeft(),view.getTop(),view.getRight(),view.getBottom());}
    private void liveContinuity()throws Exception {
        onMain(()->{Method start=MainActivity.class.getDeclaredMethod("startTransport",int.class);start.setAccessible(true);start.invoke(activity,Peer.LAN);return null;});
        await(()->onMain(()->{Object service=field(activity,"service");return service!=null&&Boolean.TRUE.equals(field(service,"foreground"));}),"Production start action retains a foreground service before recreation");
        await(()->onMain(()->controller.lanListening),"LAN reception starts");
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
        progress("Noise file is in flight; foreground service is retained");
        waitForIdleSync();onMain(()->{((ScrollView)field(activity,"messageScroll")).scrollTo(0,dp(500));return null;});
        String anchor=onMain(()->readingAnchor(activity));check(anchor!=null,"Reading anchor available before recreation");
        MainActivity old=activity;
        onMain(()->{old.recreate();return null;});await(()->latest.get()!=null&&latest.get()!=old,"Activity recreated");activity=latest.get();
        await(()->onMain(()->field(activity,"controller")==controller),"Started foreground service/controller survives recreation");
        await(()->onMain(()->anchor.equals(readingAnchor(activity))),"Reading message survives recreation");
        onMain(()->{EditText draft=field(activity,"composer");check(draft.getText().toString().equals("Unsent across recreation")&&draft.getSelectionStart()==3&&draft.getSelectionEnd()==8,"Draft and caret survive native recreation");check(field(controller,"active")==wire&&field(controller,"transfers")==files&&controller.connected,"UI recreation keeps the same Noise session");return null;});
        progress("Activity recreation preserves controller, Noise, draft and reading anchor");
        shell("wm size 900x1600");shell("wm density 320");
        await(()->onMain(()->{MainActivity m=latest.get();if(m==null||field(m,"controller")!=controller)return false;AdaptiveScaffold root=field(m,"root");View chat=field(m,"chatPage"),nav=field(m,"bottomNav");return root.getWidth()>0&&root.getWidth()<=dp(460)&&!root.dualPane()&&chat.getVisibility()==View.VISIBLE&&nav.getVisibility()==View.GONE;}),"Actual compact window retains the selected chat while hiding the rail");activity=latest.get();
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
        progress("Window/font changes retain file engine; original SHA-256 and durable receipt verified");
        android.view.inputmethod.InputConnection input=onMain(()->{EditText composer=field(activity,"composer");composer.requestFocus();return composer.onCreateInputConnection(new android.view.inputmethod.EditorInfo());});
        check(input!=null,"Composer exposes the real IME input connection");
        onMain(()->{EditText composer=field(activity,"composer");input.beginBatchEdit();input.setSelection(0,composer.length());input.setComposingText("IME composition",1);input.endBatchEdit();check(android.view.inputmethod.BaseInputConnection.getComposingSpanStart(composer.getText())>=0,"System IME establishes composition");composer.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_ENTER));input.finishComposingText();composer.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_ENTER));return null;});Thread.sleep(300);check(remoteTexts.get()==0,"IME commit does not accidentally send on key release");
        onMain(()->{EditText composer=field(activity,"composer");input.beginBatchEdit();input.setSelection(0,composer.length());input.commitText("Committed keyboard message",1);input.endBatchEdit();composer.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_ENTER));composer.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_ENTER));return null;});await(()->remoteTexts.get()==1,"Hardware Enter sends one encrypted text");onMain(()->{input.closeConnection();return null;});
    }
    private void discoveryDeadline()throws Exception {
        AtomicReference<AndroidFilePlatform> platform=new AtomicReference<>();CountDownLatch failed=new CountDownLatch(1);AtomicLong elapsed=new AtomicLong(),scanStarted=new AtomicLong();long start=SystemClock.elapsedRealtime();
        onMain(()->{
            AndroidFilePlatform p=new AndroidFilePlatform(getTargetContext(),UUID.randomUUID().toString(),"Test",UUID.randomUUID().toString());platform.set(p);
            set(p,"directFailed",(java.util.function.Consumer<Throwable>)e->{elapsed.set(SystemClock.elapsedRealtime()-start);failed.countDown();});set(p,"directOpened",(java.util.function.Consumer<AutomaticFileRoutes.Opened>)s->{});
            Object transport=field(p,"direct");TransportListener listener=field(transport,"listener");listener.onListening(Peer.WIFI_DIRECT,UiText.of("directAdvertising"));
            new Handler(Looper.getMainLooper()).postDelayed(()->listener.onListening(Peer.WIFI_DIRECT,UiText.of("directAdvertising")),2000);
            new Handler(Looper.getMainLooper()).postDelayed(()->{scanStarted.set(SystemClock.elapsedRealtime());listener.onServiceDiscoveryStarted(Peer.WIFI_DIRECT);},3000);
            new Handler(Looper.getMainLooper()).postDelayed(()->listener.onServiceDiscoveryStarted(Peer.WIFI_DIRECT),4000);return null;
        });
        try{check(failed.await(10,TimeUnit.SECONDS),"Preparation and five-second discovery deadline complete");long scanning=start+elapsed.get()-scanStarted.get();check(elapsed.get()>=7900&&scanning>=4900&&scanning<6500,"Preparation cannot steal scan time and duplicate ACK cannot extend it: total="+elapsed.get()+", scan="+scanning);}
        finally{onMain(()->{platform.get().close();return null;});}
    }
    private static String readingAnchor(MainActivity activity)throws Exception{Method method=MainActivity.class.getDeclaredMethod("captureTimelinePosition");method.setAccessible(true);Object position=method.invoke(activity);return field(position,"anchor");}
    private static String hex(byte[] values){StringBuilder out=new StringBuilder();for(byte value:values)out.append(String.format(java.util.Locale.ROOT,"%02x",value&255));return out.toString();}
    private void progress(String text){Bundle status=new Bundle();status.putString("stream",text+"\n");sendStatus(0,status);}
    private int dp(int n){return Math.round(n*getTargetContext().getResources().getDisplayMetrics().density);}
    private void measure(View root,int w,int h){root.measure(View.MeasureSpec.makeMeasureSpec(dp(w),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(dp(h),View.MeasureSpec.EXACTLY));root.layout(0,0,dp(w),dp(h));}
    private void screenshot(String name)throws Exception {waitForIdleSync();CountDownLatch frames=new CountDownLatch(1);onMain(()->{activity.getWindow().getDecorView().postOnAnimation(()->activity.getWindow().getDecorView().postOnAnimation(frames::countDown));return null;});check(frames.await(3,TimeUnit.SECONDS),"Screenshot waits for laid-out frames");waitForIdleSync();Bitmap shot=getUiAutomation().takeScreenshot();if(shot==null)return;File dir=new File(getTargetContext().getExternalFilesDir(null),"adaptive");dir.mkdirs();try(var out=new FileOutputStream(new File(dir,name))){shot.compress(Bitmap.CompressFormat.PNG,100,out);}finally{shot.recycle();}}
    private void shell(String command){try(var fd=getUiAutomation().executeShellCommand(command);var in=new FileInputStream(fd.getFileDescriptor())){in.transferTo(OutputStream.nullOutputStream());}catch(Exception ignored){}}
    private <T>T onMain(Callable<T> task)throws Exception {Object[] result=new Object[2];runOnMainSync(()->{try{result[0]=task.call();}catch(Throwable error){result[1]=error;}});if(result[1]!=null)throw new Exception((Throwable)result[1]);@SuppressWarnings("unchecked")T value=(T)result[0];return value;}
    private void await(Callable<Boolean> test,String message)throws Exception {long end=SystemClock.elapsedRealtime()+15000;while(SystemClock.elapsedRealtime()<end){if(test.call()){checks++;return;}Thread.sleep(40);}throw new AssertionError(message);}
    private void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
    @SuppressWarnings("unchecked")private static <T>T field(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return (T)f.get(o);}
    private static void set(Object o,String name,Object value)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value);}
    private static void invoke(Object o,String name)throws Exception{invoke(o,name,new Class<?>[]{});}
    private static void invoke(Object o,String name,Class<?>[] types,Object...args)throws Exception{Method m=o.getClass().getDeclaredMethod(name,types);m.setAccessible(true);m.invoke(o,args);}
    private static StreamConnection stream(Socket socket){return new StreamConnection(){public InputStream input()throws IOException{return socket.getInputStream();}public OutputStream output()throws IOException{return socket.getOutputStream();}public String label(){return "adaptive loopback";}public void close()throws IOException{socket.close();}};}
}
