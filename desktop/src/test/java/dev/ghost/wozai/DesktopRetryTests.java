package dev.ghost.wozai;

import dev.ghost.nearbyim.core.*;
import dev.ghost.nearbyim.i18n.UiText;
import java.awt.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.*;
import javax.swing.*;

/** Real native controls and approved Noise sessions exercise the resend boundary. */
public final class DesktopRetryTests {
    private static int checks;
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
    private static final class Events implements DesktopClient.Listener {
        DesktopClient client;volatile DesktopClient.State state;
        public void changed(DesktopClient.State state){this.state=state;}
        public void request(DesktopClient.Request request){client.approve(request,true);}
        public void notice(UiText text){}
    }
    private static Method method(String name,Class<?>...arguments){try{return DesktopClient.class.getMethod(name,arguments);}catch(NoSuchMethodException missing){throw new AssertionError("Production desktop resend API missing: "+name,missing);}}
    private static CompletableFuture<?> call(Method method,Object target,Object...arguments)throws Exception {return (CompletableFuture<?>)method.invoke(target,arguments);}
    private static Object field(Object target,String name)throws Exception {Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);}
    private static List<Component> all(Component root){List<Component> values=new ArrayList<>();values.add(root);if(root instanceof Container container)for(Component child:container.getComponents())values.addAll(all(child));return values;}
    private static JButton button(MessagePane pane,String label){return all(pane).stream().filter(value->value instanceof JButton b&&label.equals(b.getText())).map(value->(JButton)value).findFirst().orElseThrow(()->new AssertionError("Failed outgoing card has no native resend button"));}
    private static DesktopStore.Message message(boolean outgoing,String state)throws Exception {
        AttachmentInfo info=AttachmentInfo.v2(UUID.randomUUID().toString(),"sample.txt","text/plain",3,state.equals("delivered")?"0".repeat(64):null,1);
        return new DesktopStore.Message(info.id,info.name,1,outgoing,"unknown",1,new AttachmentRecord(info,outgoing,state,state.equals("delivered")?3:0));
    }
    @SuppressWarnings({"rawtypes","unchecked"})
    private static void nativeControls()throws Exception {
        AtomicReference<Throwable> error=new AtomicReference<>();
        SwingUtilities.invokeAndWait(()->{try{
            AppTheme.install(false);Strings strings=new Strings("zh-Hans");MessagePane pane=new MessagePane();pane.setSize(640,500);List<String> actions=new ArrayList<>();pane.actions((message,action)->actions.add(message.id()+":"+action));
            DesktopStore.Message failed=message(true,"failed");pane.render(List.of(failed),strings);JButton retry=button(pane,strings.text("attachmentRetry"));retry.doClick();retry.doClick();
            check(actions.equals(List.of(failed.id()+":retry")),"Rapid native resend clicks dispatched twice");check(!retry.isEnabled()&&strings.text("attachmentRetrying").equals(retry.getText()),"Click did not show immediate pending feedback");
            Class enumType=Class.forName("dev.ghost.nearbyim.core.AttachmentBatchQueue$RetryState");Method state=MessagePane.class.getDeclaredMethod("retryState",Function.class);state.setAccessible(true);Method batch=MessagePane.class.getDeclaredMethod("retryableBatch",Predicate.class);batch.setAccessible(true);
            state.invoke(pane,(Function<DesktopStore.Message,Object>)m->Enum.valueOf(enumType,"RETRIED"));pane.render(List.of(failed),strings);check(!button(pane,strings.text("attachmentRetried")).isEnabled(),"Admitted resend card remained enabled");
            state.invoke(pane,(Function<DesktopStore.Message,Object>)m->Enum.valueOf(enumType,"UNAVAILABLE"));batch.invoke(pane,(Predicate<DesktopStore.Message>)m->true);pane.render(List.of(failed),strings);button(pane,strings.text("attachmentRetry")).doClick();check(actions.contains(failed.id()+":reselect"),"Unavailable source did not offer reselection");
            state.invoke(pane,(Function<DesktopStore.Message,Object>)m->Enum.valueOf(enumType,"INELIGIBLE"));pane.render(List.of(failed),strings);int before=actions.size();JButton ineligible=button(pane,strings.text("attachmentRetryIneligible"));ineligible.doClick();check(!ineligible.isEnabled()&&actions.size()==before,"Canceled retry lineage offered an unusable source picker");
            state.invoke(pane,(Function<DesktopStore.Message,Object>)m->Enum.valueOf(enumType,"AVAILABLE"));pane.render(List.of(failed),strings);
            JTextArea body=all(pane).stream().filter(c->c instanceof JTextArea).map(c->(JTextArea)c).findFirst().orElseThrow();JPopupMenu menu=body.getComponentPopupMenu();check(menu!=null,"Failed batch card has no anchored menu");JMenuItem retryBatch=Arrays.stream(menu.getComponents()).filter(c->c instanceof JMenuItem item&&strings.text("attachmentRetryBatch").equals(item.getText())).map(c->(JMenuItem)c).findFirst().orElseThrow();retryBatch.doClick();check(actions.contains(failed.id()+":retryBatch"),"Batch resend lost its clicked failed-item anchor");
            pane.render(List.of(message(false,"failed"),message(true,"delivered")),strings);check(all(pane).stream().noneMatch(c->c instanceof JButton b&&strings.text("attachmentRetry").equals(b.getText())),"Incoming/delivered cards offer resend");
            pane.render(List.of(message(true,"paused")),strings);JButton resume=all(pane).stream().filter(c->c instanceof JButton b&&strings.text("attachmentResume").equals(b.getToolTipText())).map(c->(JButton)c).findFirst().orElseThrow();resume.doClick();check(actions.get(actions.size()-1).endsWith(":resume"),"Paused card no longer dispatches Continue");
        }catch(Throwable failure){error.set(failure);}});if(error.get()!=null)throw new AssertionError("Native resend control regression",error.get());
    }
    private static AttachmentRecord record(DesktopStore store,String peer,String id)throws Exception {return store.messages(peer).stream().filter(message->message.outgoing()&&message.id().equals(id)).map(DesktopStore.Message::attachment).findFirst().orElseThrow();}
    private interface Check {boolean test()throws Exception;}
    private static void until(Check condition)throws Exception {long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);while(System.nanoTime()<end){if(condition.test())return;Thread.sleep(10);}throw new AssertionError("Actual resend did not reach expected state");}
    private static Throwable refused(CompletableFuture<?> result)throws Exception {try{result.get(10,TimeUnit.SECONDS);}catch(ExecutionException expected){return expected.getCause();}throw new AssertionError("Invalid/stale resend was accepted");}
    public static void main(String[] args)throws Exception {
        if(args.length>0&&args[0].equals("--gui")){nativeControls();System.out.println("DesktopRetryTests: native resend controls passed");return;}
        Method retry=method("retryAttachment",String.class,String.class),batch=method("retryFailedBatch",String.class,String.class),replace=method("retryAttachmentWithSelection",IdentityVerification.class,String.class,Path.class),expectedRetry=method("retryAttachment",IdentityVerification.class,String.class);
        nativeControls();
        Path root=Files.createTempDirectory("desktop-retry-");Path ar=root.resolve("a"),br=root.resolve("b");Files.createDirectories(ar);Files.createDirectories(br);Events ea=new Events(),eb=new Events();var ai=DesktopIdentity.load(ar.resolve("identity.properties"));var bi=DesktopIdentity.load(br.resolve("identity.properties"));
        try(DesktopStore as=new DesktopStore(ar);DesktopStore bs=new DesktopStore(br);DesktopClient a=new DesktopClient(as,ai,ea);DesktopClient b=new DesktopClient(bs,bi,eb)) {
            ea.client=a;eb.client=b;var listening=b.listen().get(10,TimeUnit.SECONDS);a.connect(listening.endpoints().get(0),bi.id()).get(10,TimeUnit.SECONDS);until(()->ea.state!=null&&eb.state!=null&&ea.state.phase().equals("ready")&&eb.state.phase().equals("ready"));
            ClientAttachmentTransfers bridge=(ClientAttachmentTransfers)field(field(a,"current"),"transfers");
            Path missing=root.resolve("missing.txt");a.sendAttachments(bi.id(),List.of(missing)).get(10,TimeUnit.SECONDS);until(()->a.batchItems(bi.id()).stream().anyMatch(item->item.state().equals("failed")));String failed=a.batchItems(bi.id()).get(0).info().id;
            Throwable unavailable=refused(call(retry,a,bi.id(),failed));check(unavailable.getClass().getSimpleName().equals("RetrySourceUnavailableException"),"Missing source did not request reselection");
            Path replacement=root.resolve("replacement.txt");Files.writeString(replacement,"new readable bytes");IdentityVerification expected=a.verification(bi.id()).get(5,TimeUnit.SECONDS);CompletableFuture<?> first=call(replace,a,expected,failed,replacement),second=call(replace,a,expected,failed,replacement);String child=(String)first.get(10,TimeUnit.SECONDS);check(child.equals(second.get(10,TimeUnit.SECONDS))&&!child.equals(failed),"Double resend failed to reuse one new task ID");until(()->record(as,bi.id(),child).state.equals("delivered"));check(record(as,bi.id(),failed).state.equals("failed"),"Resend erased the original failure");check(a.endToEndEncrypted(bi.id()),"Resend left the approved Noise channel");
            refused(call(expectedRetry,a,new IdentityVerification(expected.peerId,bi.signer().publicKey(),expected.remoteRoot,expected.generation,false),failed));refused(call(expectedRetry,a,new IdentityVerification(expected.peerId,expected.localRoot,ai.signer().publicKey(),expected.generation,false),failed));refused(call(expectedRetry,a,new IdentityVerification(expected.peerId,expected.localRoot,expected.remoteRoot,UUID.randomUUID().toString(),false),failed));
            refused(call(retry,a,bi.id(),child));check(a.batchItems(bi.id()).stream().filter(item->item.info().id.equals(child)).count()==1,"Delivered resend created another task");refused(call(retry,a,ai.id(),failed));refused(call(retry,a,bi.id(),UUID.randomUUID().toString()));
            for(DesktopStore.Message ineligible:List.of(message(false,"failed"),message(true,"paused"),message(true,"canceled"))){as.attachment(bi.id(),ineligible.attachment());refused(call(retry,a,bi.id(),ineligible.id()));}
            refused(a.attachmentAction(bi.id(),failed,true,"retryUnknown"));refused(a.batchAction(bi.id(),"retryUnknown"));check(record(as,bi.id(),failed).state.equals("failed"),"Unknown resend action changed stored state");
            Path sourceDirectory=root.resolve("batch-sources"),hiddenDirectory=root.resolve("temporarily-unavailable");Files.createDirectories(sourceDirectory);Path aa=sourceDirectory.resolve("batch-a.txt"),ab=sourceDirectory.resolve("batch-a-missing.txt"),ba=sourceDirectory.resolve("batch-b.txt");Files.writeString(aa,"same batch");Files.writeString(ab,"unreadable batch member");Files.writeString(ba,"other batch");
            bridge.pauseBatch().get(5,TimeUnit.SECONDS);a.sendAttachments(bi.id(),List.of(aa,ab)).get(10,TimeUnit.SECONDS);a.sendAttachments(bi.id(),List.of(ba)).get(10,TimeUnit.SECONDS);Files.move(sourceDirectory,hiddenDirectory);bridge.resumeBatch().get(5,TimeUnit.SECONDS);until(()->a.batchItems(bi.id()).stream().filter(item->item.state().equals("failed")).count()==4);Files.move(hiddenDirectory,sourceDirectory);Files.delete(ab);
            String anchor=a.batchItems(bi.id()).stream().filter(item->item.info().name.equals("batch-a.txt")).findFirst().orElseThrow().info().id;String other=a.batchItems(bi.id()).stream().filter(item->item.info().name.equals("batch-b.txt")).findFirst().orElseThrow().info().id;
            Object result=call(batch,a,bi.id(),anchor).get(10,TimeUnit.SECONDS);List<?> queued=(List<?>)result.getClass().getMethod("queuedIds").invoke(result),unreadable=(List<?>)result.getClass().getMethod("unavailableIds").invoke(result);check(queued.size()==1&&unreadable.size()==1,"Partial batch retry did not report readable/unreadable files");check(a.batchItems(bi.id()).stream().filter(item->item.info().id.equals(other)).allMatch(item->item.state().equals("failed")),"Batch retry included a different selection batch");
            DesktopStore.Message pruned=message(true,"failed");as.attachment(bi.id(),pruned.attachment());String recovered=(String)call(replace,a,expected,pruned.id(),replacement).get(10,TimeUnit.SECONDS);check(!recovered.equals(pruned.id()),"Explicit replacement rejected a stored failure missing from the queue");
            // Delay source description so disconnect races with a genuine replacement completion.
            ThreadPoolExecutor io=(ThreadPoolExecutor)field(a,"attachmentIO");CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);io.execute(()->{entered.countDown();try{release.await();}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}});check(entered.await(5,TimeUnit.SECONDS),"Replacement test could not block source description");
            DesktopStore.Message stale=message(true,"failed");as.attachment(bi.id(),stale.attachment());CompletableFuture<?> selecting=call(replace,a,expected,stale.id(),replacement);until(()->!io.getQueue().isEmpty());a.disconnect().get(10,TimeUnit.SECONDS);release.countDown();refused(selecting);refused(call(expectedRetry,a,expected,failed));check(record(as,bi.id(),stale.id()).state.equals("failed"),"Stale selection altered the original failure");
        }finally{try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
        System.out.println("DesktopRetryTests: "+checks+" checks passed (native resend/Continue, Noise retry, dedupe, partial batch, reselection, stored failure and stale session)");
    }
}
