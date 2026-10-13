package dev.ghost.nearbyim;
import android.content.*;
import android.content.res.AssetFileDescriptor;
import android.database.*;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import java.io.*;
/** A read-only, synthetic provider. No client private files are reachable here. */
public final class SourceTestProvider extends ContentProvider {
    private File fixture;
    private volatile boolean retryReadable=true;
    public boolean onCreate(){fixture=new File(getContext().getCacheDir(),"source-fixture.bin");reset();return true;}
    private void reset(){try(FileOutputStream out=new FileOutputStream(fixture)){out.write(new byte[]{0,1,2,3,4,5,6,7});}catch(IOException error){throw new IllegalStateException(error);}}
    public Bundle call(String method,String argument,Bundle extras){if(method.equals("reset"))reset();else if(method.equals("retry-deny"))retryReadable=false;else if(method.equals("retry-allow"))retryReadable=true;else if(method.equals("large")){try(FileOutputStream out=new FileOutputStream(fixture)){byte[] block=new byte[1024*1024];new java.util.Random(83).nextBytes(block);for(int n=0;n<8;n++)out.write(block);}catch(IOException error){throw new IllegalStateException(error);}}else if(method.equals("mutate")){try(RandomAccessFile file=new RandomAccessFile(fixture,"rw")){file.seek(2);file.write(42);fixture.setLastModified(System.currentTimeMillis()-5000);}catch(IOException error){throw new IllegalStateException(error);}}else throw new IllegalArgumentException();return new Bundle();}
    public String getType(Uri uri){return uri.getPath().startsWith("/reopened/")?"image/jpeg":"application/octet-stream";}
    public Cursor query(Uri uri,String[] projection,String selection,String[] args,String order){String[] columns=projection==null?new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE}:projection;MatrixCursor cursor=new MatrixCursor(columns);Object[] row=new Object[columns.length];for(int i=0;i<columns.length;i++)row[i]=columns[i].equals(OpenableColumns.DISPLAY_NAME)?uri.getPath().equals("/retry")?"retry.bin":"fixture.bin":uri.getPath().equals("/pipe")?null:uri.getPath().equals("/slice")?3L:fixture.length();cursor.addRow(row);return cursor;}
    public AssetFileDescriptor openAssetFile(Uri uri,String mode)throws FileNotFoundException {
        if(!mode.equals("r"))throw new FileNotFoundException("Fixture is read-only");String path=uri.getPath();if(path.equals("/denied")||path.equals("/retry")&&!retryReadable)throw new SecurityException("Revoked synthetic grant");
        if(path.equals("/pipe"))try{ParcelFileDescriptor[] pipe=ParcelFileDescriptor.createPipe();Thread writer=new Thread(()->{try(OutputStream output=new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])){output.write(new byte[]{8,9,10});}catch(IOException ignored){}});writer.setDaemon(true);writer.start();return new AssetFileDescriptor(pipe[0],0,AssetFileDescriptor.UNKNOWN_LENGTH);}catch(IOException error){throw new FileNotFoundException(error.toString());}
        if(path.startsWith("/reopened/"))try{
            // A valid provider may materialize the same document in a fresh inode on each open.
            File materialized=File.createTempFile("source-view-",".jpg",getContext().getCacheDir());
            try(InputStream input=new FileInputStream(fixture);OutputStream output=new FileOutputStream(materialized)){byte[] buffer=new byte[8192];int n;while((n=input.read(buffer))!=-1)output.write(buffer,0,n);}
            ParcelFileDescriptor fd=ParcelFileDescriptor.open(materialized,ParcelFileDescriptor.MODE_READ_ONLY);long length=materialized.length();materialized.delete();return new AssetFileDescriptor(fd,0,length);
        }catch(IOException error){throw new FileNotFoundException(error.toString());}
        if(!path.equals("/file")&&!path.equals("/slice")&&!path.equals("/retry"))throw new FileNotFoundException("Unknown fixture");return new AssetFileDescriptor(ParcelFileDescriptor.open(fixture,ParcelFileDescriptor.MODE_READ_ONLY),path.equals("/slice")?2:0,path.equals("/slice")?3:fixture.length());
    }
    public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException();}
    public int update(Uri uri,ContentValues values,String selection,String[] args){throw new UnsupportedOperationException();}
    public int delete(Uri uri,String selection,String[] args){throw new UnsupportedOperationException();}
}
