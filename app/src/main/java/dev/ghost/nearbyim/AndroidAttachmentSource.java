package dev.ghost.nearbyim;

import android.content.*;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.OpenableColumns;
import android.system.*;
import dev.ghost.nearbyim.core.*;
import dev.ghost.nearbyim.i18n.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Read-only SAF source. Positional reads respect an AssetFileDescriptor's subrange. */
public final class AndroidAttachmentSource implements AttachmentSource {
    private static final Map<String,Integer> OPEN=new HashMap<>();
    private final ContentResolver resolver;
    private final Path attachments;
    private final AssetFileDescriptor asset;
    private final Uri uri;
    private final String generation,stamp,name,mime;
    private final long start,length;
    private final boolean seekable;
    private boolean streamed;
    private volatile boolean closed;
    public AndroidAttachmentSource(Context context,Uri uri)throws IOException {this(context,uri,UUID.randomUUID().toString());}
    public AndroidAttachmentSource(Context context,Uri uri,String generation)throws IOException {
        if(uri==null||!"content".equals(uri.getScheme())||generation==null||!generation.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"))throw new IOException("Invalid document source");
        this.uri=uri;this.generation=generation;resolver=context.getContentResolver();attachments=context.getFilesDir().toPath().resolve("attachments");AssetFileDescriptor opened=null;
        try {
            String display="attachment";long queried=-1;
            try(Cursor cursor=resolver.query(uri,new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE},null,null,null)) {
                if(cursor!=null&&cursor.moveToFirst()){int n=cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME),s=cursor.getColumnIndex(OpenableColumns.SIZE);if(n>=0&&!cursor.isNull(n))display=cursor.getString(n);if(s>=0&&!cursor.isNull(s))queried=cursor.getLong(s);}
            }
            if(display==null||display.trim().isEmpty())display="attachment";display=display.replaceAll("[\\p{Cntrl}]","_");
            if(display.codePointCount(0,display.length())>255)display=display.substring(0,display.offsetByCodePoints(0,255));
            String type=resolver.getType(uri);if(type==null||!type.matches("[A-Za-z0-9!#$&^_.+/-]{1,127}"))type="application/octet-stream";name=display;mime=type;
            opened=resolver.openAssetFileDescriptor(uri,"r");if(opened==null)throw new FileNotFoundException("Document unavailable");
            StructStat stat=Os.fstat(opened.getFileDescriptor());start=opened.getStartOffset();if(start<0)throw new IOException("Invalid asset offset");
            long declared=opened.getDeclaredLength();boolean regular=OsConstants.S_ISREG(stat.st_mode);
            length=declared>=0?declared:regular?stat.st_size-start:queried;
            if(length< -1||length>TransferLimits.MAX_FILE_BYTES||length>=0&&start>Long.MAX_VALUE-length||regular&&(start>stat.st_size||length>stat.st_size-start))throw new LocalizedIOException(UiText.of("attachmentTooLarge"));
            boolean positioned=false;try{Os.lseek(opened.getFileDescriptor(),0,OsConstants.SEEK_CUR);positioned=true;}catch(ErrnoException pipe){if(pipe.errno!=OsConstants.ESPIPE)throw pipe;}
            seekable=regular&&positioned&&length>=0;stamp=statStamp(stat);asset=opened;verifyUnchanged();
            // Temporary grants remain useful when a provider does not offer a persistable grant.
            synchronized(OPEN){try{resolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(SecurityException unsupported){}OPEN.put(uri.toString(),OPEN.getOrDefault(uri.toString(),0)+1);}
        }catch(SecurityException error){if(opened!=null)opened.close();throw new LocalizedIOException(UiText.of("attachmentSourcePermission"),error);}
        catch(ErrnoException error){if(opened!=null)opened.close();throw new LocalizedIOException(UiText.of("attachmentSourceUnavailable"),error);}
        catch(IOException|RuntimeException error){if(opened!=null)opened.close();throw error;}
    }
    public String name(){return name;}
    public String mime(){return mime;}
    public long size(){return length;}
    public String generation(){return generation;}
    public boolean seekable(){return seekable;}
    public String persistentReference(){return uri+"\n"+start+"\n"+length+"\n"+stamp;}
    public void verifyUnchanged()throws IOException {
        if(closed)throw new IOException("Document source closed");if(!seekable)return;
        try{if(!stamp.equals(statStamp(Os.fstat(asset.getFileDescriptor()))))throw new LocalizedIOException(UiText.of("attachmentSourceChanged"));}
        catch(ErrnoException error){throw new LocalizedIOException(UiText.of("attachmentSourceUnavailable"),error);}
    }
    public synchronized InputStream open(long offset)throws IOException {
        if(closed)throw new IOException("Document source closed");TransferLimits.validateRange(length<0?TransferLimits.MAX_FILE_BYTES:length,offset,0);verifyUnchanged();
        if(!seekable){if(offset!=0||streamed)throw new IOException("Non-seekable source requires a snapshot");streamed=true;return asset.createInputStream();}
        return new InputStream(){
            private long position=offset;private boolean viewClosed;
            public int read()throws IOException{byte[] one=new byte[1];int n=read(one,0,1);return n<0?-1:one[0]&255;}
            public int read(byte[] bytes,int from,int count)throws IOException {
                if(from<0||count<0||from>bytes.length-count)throw new IndexOutOfBoundsException();if(viewClosed||closed)throw new IOException("Document view closed");
                if(count==0)return 0;if(position==length)return -1;
                try{int n=Os.pread(asset.getFileDescriptor(),bytes,from,(int)Math.min(count,length-position),start+position);if(n==0)throw new EOFException("Document truncated");position+=n;return n;}
                catch(ErrnoException error){throw new LocalizedIOException(UiText.of("attachmentSourceUnavailable"),error);}
            }
            public void close(){viewClosed=true;}
        };
    }
    private static String statStamp(StructStat stat){return stat.st_dev+":"+stat.st_ino+":"+stat.st_size+":"+stat.st_mtime+":"+stat.st_ctime+(Build.VERSION.SDK_INT>=27?nanoseconds(stat):"");}
    @android.annotation.TargetApi(27) private static String nanoseconds(StructStat stat){return ":"+stat.st_mtim.tv_nsec+":"+stat.st_ctim.tv_nsec;}
    public synchronized void close()throws IOException{if(closed)return;closed=true;synchronized(OPEN){int count=OPEN.getOrDefault(uri.toString(),1)-1;if(count==0)OPEN.remove(uri.toString());else OPEN.put(uri.toString(),count);}asset.close();}
    public void discard()throws IOException {
        close();releaseUnused(resolver,attachments,uri);
    }
    /** Run after canceled task journals are durable and their source handles have closed. */
    public static void releaseUnusedGrants(Context context)throws IOException {
        ContentResolver resolver=context.getContentResolver();Path attachments=context.getFilesDir().toPath().resolve("attachments");
        for(UriPermission grant:resolver.getPersistedUriPermissions())if(grant.isReadPermission())releaseUnused(resolver,attachments,grant.getUri());
    }
    private static void releaseUnused(ContentResolver resolver,Path attachments,Uri uri)throws IOException {
        synchronized(OPEN){if(OPEN.containsKey(uri.toString()))return;}
        if(Files.isSymbolicLink(attachments))throw new IOException("Unsafe attachment directory");
        if(Files.exists(attachments,LinkOption.NOFOLLOW_LINKS))try(var directories=Files.list(attachments)){for(Path peer:(Iterable<Path>)directories::iterator){
            if(Files.isSymbolicLink(peer)||!Files.isDirectory(peer,LinkOption.NOFOLLOW_LINKS))throw new IOException("Unsafe attachment directory");Path journal=peer.resolve(".tasks-v2");if(!Files.exists(journal,LinkOption.NOFOLLOW_LINKS))continue;
            for(TransferCheckpoint saved:new TransferCheckpointStore(journal).list())if(saved.state()!=TransferCheckpoint.State.COMPLETE&&saved.state()!=TransferCheckpoint.State.CANCELED&&saved.sourceReference().startsWith(uri+"\n"))return;
        }}
        synchronized(OPEN){if(OPEN.containsKey(uri.toString()))return;try{resolver.releasePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(SecurityException absent){}}
    }
}
