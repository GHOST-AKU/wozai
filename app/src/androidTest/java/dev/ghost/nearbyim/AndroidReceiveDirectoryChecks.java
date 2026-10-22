package dev.ghost.nearbyim;

import android.content.*;
import android.database.*;
import android.database.sqlite.*;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import dev.ghost.nearbyim.core.*;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** SAF grants and public file behavior, using a test-only real DocumentsProvider. */
final class AndroidReceiveDirectoryChecks {
    private static int checks;
    private static final Uri CONTROL=Uri.parse("content://dev.ghost.nearbyim.test.sources/file");
    static final int FLAGS=Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION;
    static Uri tree(String id){return DocumentsContract.buildTreeDocumentUri(DirectoryTestProvider.AUTHORITY,id);}
    static void command(Context context,String command,String root){Bundle bundle=new Bundle();bundle.putString("root",root);context.getContentResolver().call(CONTROL,command,context.getPackageName(),bundle);}
    static int run(Context base)throws Exception {
        checks=0;Path root=Files.createTempDirectory(base.getCacheDir().getCanonicalFile().toPath(),"native-receive-dir-");
        Context context=new ContextWrapper(base){
            public File getFilesDir(){File file=root.resolve("files").toFile();file.mkdirs();return file;}
            public SharedPreferences getSharedPreferences(String name,int mode){return base.getSharedPreferences(root.getFileName()+name,mode);}
            public File getDatabasePath(String name){return root.resolve(name).toFile();}
            public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory factory){return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name),factory);}
            public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory factory,DatabaseErrorHandler handler){return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).getPath(),factory,handler);}
        };
        String peer=UUID.randomUUID().toString();byte[] bytes={3,1,4,1,5};Uri first=null,last=null;
        command(base,"directory-reset","one");for(String id:List.of("one","two","readonly"))command(base,"directory-grant",id);
        try(ChatStore store=new ChatStore(context)){
            AttachmentRecord record=received(store,peer,"报告 😀.txt",bytes);
            try(AttachmentLocations locations=new AttachmentLocations(context)){
                check(locations.directory()==null,"Default save directory changed without a user choice");
                check(locations.saveReceived(peer,record,false).get(10,TimeUnit.SECONDS)==null&&count(context,"one")==0,"Disabled folder setting created a document");
                refused(()->locations.selectDirectory(tree("one"),Intent.FLAG_GRANT_READ_URI_PERMISSION).get(10,TimeUnit.SECONDS));
                refused(()->locations.selectDirectory(Uri.parse("file:///sdcard/Download"),FLAGS).get(10,TimeUnit.SECONDS));
                check(locations.selectDirectory(tree("one"),FLAGS).get(10,TimeUnit.SECONDS).uri().equals(tree("one")),"Folder selection lost its tree URI");
                refused(()->locations.selectDirectory(tree("readonly"),FLAGS).get(10,TimeUnit.SECONDS));
                check(locations.directory().uri().equals(tree("one")),"Rejected folder replaced the valid selection");
                Intent picker=AttachmentLocations.directoryPicker(locations.directory());check(Intent.ACTION_OPEN_DOCUMENT_TREE.equals(picker.getAction())&&(picker.getFlags()&FLAGS)==FLAGS&&tree("one").equals(picker.getParcelableExtra(DocumentsContract.EXTRA_INITIAL_URI)),"Folder picker did not request a persisted read/write tree");
                AndroidAttachmentSource.releaseUnusedGrants(context);check(persisted(context,"one"),"Source cleanup revoked the selected receive tree");
                // Hold the serial worker so the history lookup definitely precedes the receipt callback.
                var workerField=AttachmentLocations.class.getDeclaredField("worker");workerField.setAccessible(true);CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
                ((ThreadPoolExecutor)workerField.get(locations)).execute(()->{entered.countDown();try{if(!release.await(10,TimeUnit.SECONDS))throw new AssertionError("Lookup race gate timed out");}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}});
                check(entered.await(10,TimeUnit.SECONDS),"Directory worker race gate did not start");CompletableFuture<AttachmentDirectoryStore.State> automatic;
                try{locations.inspectReceived(peer,record);automatic=locations.saveReceived(peer,record,false);locations.selectDirectory(tree("two"),FLAGS).get(10,TimeUnit.SECONDS);}finally{release.countDown();}
                check(automatic.get(10,TimeUnit.SECONDS)==AttachmentDirectoryStore.State.SAVED,"History lookup swallowed the automatic receipt save");
                var location=locations.locate(peer,record).get(10,TimeUnit.SECONDS);first=location.savedUri();check(location.kind()==AttachmentLocations.Kind.DOCUMENT&&location.availability()==AttachmentLocations.Availability.AVAILABLE,"Location still points to app-private storage");
                check(DocumentsContract.getTreeDocumentId(first).equals("one")&&locations.directory().uri().equals(tree("two")),"Changing folders redirected an already scheduled receipt");locations.selectDirectory(tree("one"),FLAGS).get(10,TimeUnit.SECONDS);
                check(Arrays.equals(bytes,read(context,first)),"Public document changed received bytes");check(name(context,first).equals(record.info.name),"Public document lost its original name");
                locations.saveReceived(peer,record,false).get(10,TimeUnit.SECONDS);check(count(context,"one")==1,"Receipt replay duplicated a saved document");
                AttachmentRecord second=received(store,peer,record.info.name,bytes);locations.saveReceived(peer,second,false).get(10,TimeUnit.SECONDS);Uri secondUri=locations.locate(peer,second).get(10,TimeUnit.SECONDS).savedUri();
                check(!first.equals(secondUri)&&count(context,"one")==2&&Arrays.equals(read(context,first),bytes),"A same-name transfer overwrote the first document");
                AttachmentRecord old=received(store,peer,"old.bin",bytes);check(locations.inspectReceived(peer,old).get(10,TimeUnit.SECONDS)==null&&count(context,"one")==2,"Selecting a folder silently copied historical files");
                command(base,"directory-fail","one");AttachmentRecord failed=received(store,peer,"retry.bin",bytes);refused(()->locations.saveReceived(peer,failed,false).get(10,TimeUnit.SECONDS));
                check(count(context,"one")==2&&store.completedAttachment(peer,failed).equals(failed),"Failed folder output leaked a partial document or changed the saved receipt");
                command(base,"directory-allow","one");locations.saveReceived(peer,failed,true).get(10,TimeUnit.SECONDS);check(count(context,"one")==3,"Manual save retry did not complete");
                AttachmentRecord corrupt=received(store,peer,"corrupt.bin",bytes);Files.write(store.attachmentFile(peer,corrupt.info,false),new byte[bytes.length]);refused(()->locations.saveReceived(peer,corrupt,false).get(10,TimeUnit.SECONDS));check(count(context,"one")==3,"Corrupt source became a completed public file");
                AttachmentDirectoryStore journal=new AttachmentDirectoryStore(context.getFilesDir().toPath().resolve("receive-directory-jobs"));
                journal.save(peer,second,new AttachmentDirectoryStore.Entry(AttachmentDirectoryStore.State.COPIED,tree("one").toString(),secondUri.toString()));
                new AttachmentExportStore(context.getFilesDir().toPath().resolve("attachment-exports")).forget(peer,second);
                try(AttachmentLocations restarted=new AttachmentLocations(context)){
                    check(restarted.directory().uri().equals(tree("one")),"Selected folder did not survive restart");
                    check(restarted.inspectReceived(peer,second).get(10,TimeUnit.SECONDS)==AttachmentDirectoryStore.State.SAVED&&secondUri.equals(restarted.locate(peer,second).get(10,TimeUnit.SECONDS).savedUri())&&count(context,"one")==3,"Crash after copy created a duplicate or lost the public location");
                    check(DocumentsContract.deleteDocument(context.getContentResolver(),first),"Fixture document was not deleted");
                    check(restarted.inspectReceived(peer,record).get(10,TimeUnit.SECONDS)==AttachmentDirectoryStore.State.MISSING,"Deleted public document was still available");
                    check(restarted.saveReceived(peer,record,false).get(10,TimeUnit.SECONDS)==AttachmentDirectoryStore.State.MISSING&&count(context,"one")==2,"Automatic save recreated a user-deleted document");
                    check(!persisted(context,"two"),"Unused previous folder grant was retained");
                    // A new picker result offers fresh permission after the unused grant was released.
                    command(base,"directory-grant","two");restarted.selectDirectory(tree("two"),FLAGS).get(10,TimeUnit.SECONDS);AndroidAttachmentSource.releaseUnusedGrants(context);
                    check(persisted(context,"one")&&persisted(context,"two"),"Folder switch revoked access to earlier saved files");
                    AttachmentRecord after=received(store,peer,"new.bin",bytes);restarted.saveReceived(peer,after,false).get(10,TimeUnit.SECONDS);last=restarted.locate(peer,after).get(10,TimeUnit.SECONDS).savedUri();check(DocumentsContract.getTreeDocumentId(last).equals("two")&&count(context,"one")==2,"New receipt ignored the changed folder");
                    AttachmentRecord partial=received(store,peer,"partial.bin",bytes);Uri partialUri=DocumentsContract.createDocument(context.getContentResolver(),DocumentsContract.buildDocumentUriUsingTree(tree("two"),"two"),"application/octet-stream","partial.bin");try(OutputStream output=context.getContentResolver().openOutputStream(partialUri,"wt")){output.write(0);}
                    journal.save(peer,partial,new AttachmentDirectoryStore.Entry(AttachmentDirectoryStore.State.PENDING,tree("two").toString(),partialUri.toString()));restarted.inspectReceived(peer,partial).get(10,TimeUnit.SECONDS);check(Arrays.equals(read(context,partialUri),bytes)&&partialUri.equals(restarted.locate(peer,partial).get(10,TimeUnit.SECONDS).savedUri()),"Restart did not finish the same pending public document");
                    command(base,"directory-revoke","two");check(restarted.locate(peer,after).get(10,TimeUnit.SECONDS).availability()==AttachmentLocations.Availability.ACCESS_LOST,"Revoked tree access silently reverted to the private original");command(base,"directory-grant","two");restarted.selectDirectory(tree("two"),FLAGS).get(10,TimeUnit.SECONDS);
                    restarted.stopDirectory();restarted.clearPeer(peer).get(10,TimeUnit.SECONDS);store.clear(peer);restarted.finishClear(peer);
                    check(!persisted(context,"one")&&!persisted(context,"two"),"Cleared unused folder grants were retained");command(base,"directory-grant","one");command(base,"directory-grant","two");
                    check(read(context,last).length==bytes.length&&count(context,"one")==2,"Clearing history deleted user-owned folder documents");
                }
            }return checks;
        }finally{for(String id:List.of("one","two","readonly"))command(base,"directory-revoke",id);command(base,"directory-reset","one");try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).collect(java.util.stream.Collectors.toList()))Files.deleteIfExists(path);}}
    }
    private static AttachmentRecord received(ChatStore store,String peer,String name,byte[] bytes)throws Exception {
        AttachmentInfo info=AttachmentInfo.v2(UUID.randomUUID().toString(),name,"application/octet-stream",bytes.length,AttachmentInfo.hex(MessageDigest.getInstance("SHA-256").digest(bytes)),1);AttachmentRecord record=new AttachmentRecord(info,false,"received",info.size);store.attachment(peer,"peer",record);Path file=store.attachmentFile(peer,info,false);Files.createDirectories(file.getParent());Files.write(file,bytes);return record;
    }
    private static byte[] read(Context context,Uri uri)throws Exception {try(InputStream input=context.getContentResolver().openInputStream(uri)){ByteArrayOutputStream output=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int count;while((count=input.read(buffer))!=-1)output.write(buffer,0,count);return output.toByteArray();}}
    private static String name(Context context,Uri uri){try(Cursor cursor=context.getContentResolver().query(uri,new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME},null,null,null)){cursor.moveToFirst();return cursor.getString(0);}}
    private static int count(Context context,String tree){try(Cursor cursor=context.getContentResolver().query(DocumentsContract.buildChildDocumentsUriUsingTree(tree(tree),tree),new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID},null,null,null)){return cursor.getCount();}}
    private static boolean persisted(Context context,String root){for(UriPermission grant:context.getContentResolver().getPersistedUriPermissions())if(grant.getUri().equals(tree(root))&&grant.isReadPermission()&&grant.isWritePermission())return true;return false;}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
    private interface Work {void run()throws Exception;}
    private static void refused(Work work)throws Exception {try{work.run();throw new AssertionError("Unsafe or failing folder operation succeeded");}catch(ExecutionException expected){checks++;}}
}
