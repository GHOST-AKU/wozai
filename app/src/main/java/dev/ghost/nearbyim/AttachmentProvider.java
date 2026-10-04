package dev.ghost.nearbyim;
import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;
import java.io.*;
import java.nio.file.*;
import java.util.*;
/** Read-only provider; grants cover only a completed application-managed attachment. */
public final class AttachmentProvider extends ContentProvider {
    public boolean onCreate(){return true;}
    private File file(Uri uri)throws FileNotFoundException {
        java.util.List<String> parts=uri.getPathSegments();
        if(parts.size()!=2||!parts.get(0).matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")||!parts.get(1).matches("(in|out)-[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}\\.[a-z0-9]{1,16}"))throw new FileNotFoundException("Invalid attachment URI");
        try{
            File root=new File(getContext().getFilesDir(),"attachments"),directory=new File(root,parts.get(0)),file=new File(directory,parts.get(1));
            if(Files.isSymbolicLink(root.toPath())||Files.isSymbolicLink(directory.toPath())||Files.isSymbolicLink(file.toPath())||!Files.isRegularFile(file.toPath(),LinkOption.NOFOLLOW_LINKS)||!file.getCanonicalPath().startsWith(root.getCanonicalPath()+File.separator))throw new FileNotFoundException("Attachment unavailable");
            return file;
        }catch(IOException e){throw new FileNotFoundException("Attachment unavailable");}
    }
    public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException {if(!"r".equals(mode))throw new FileNotFoundException("Read-only attachment");return ParcelFileDescriptor.open(file(uri),ParcelFileDescriptor.MODE_READ_ONLY);}
    public Cursor query(Uri uri,String[] projection,String selection,String[] args,String sort){
        try{File file=file(uri);String[] columns=projection==null?new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE}:projection;MatrixCursor cursor=new MatrixCursor(columns);Object[] values=new Object[columns.length];
            for(int i=0;i<columns.length;i++){if(columns[i].equals(OpenableColumns.DISPLAY_NAME)){String name=uri.getQueryParameter("name");values[i]=name==null?file.getName():name;}else if(columns[i].equals(OpenableColumns.SIZE))values[i]=file.length();}
            cursor.addRow(values);return cursor;
        }catch(FileNotFoundException e){throw new IllegalArgumentException("Attachment unavailable",e);}
    }
    public String getType(Uri uri){String mime=uri.getQueryParameter("mime");if(mime!=null&&mime.matches("[A-Za-z0-9!#$&^_.+/-]{1,127}"))return mime;String extension=MimeTypeMap.getFileExtensionFromUrl(uri.toString());String type=MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.toLowerCase(Locale.ROOT));return type==null?"application/octet-stream":type;}
    public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException("Read-only attachment");}
    public int update(Uri uri,ContentValues values,String selection,String[] args){throw new UnsupportedOperationException("Read-only attachment");}
    public int delete(Uri uri,String selection,String[] args){throw new UnsupportedOperationException("Read-only attachment");}
}
