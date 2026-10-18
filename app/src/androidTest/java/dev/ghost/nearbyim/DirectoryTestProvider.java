package dev.ghost.nearbyim;

import android.database.*;
import android.os.*;
import android.provider.*;
import java.io.*;
import java.util.*;

/** Synthetic SAF provider with real tree grants, creation and collision handling. */
public final class DirectoryTestProvider extends DocumentsProvider {
    static final String AUTHORITY="dev.ghost.nearbyim.test.directories";
    private static final String[] COLUMNS={DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_FLAGS,DocumentsContract.Document.COLUMN_SIZE};
    public boolean onCreate(){return true;}
    private File file(String id)throws FileNotFoundException {
        if(!id.matches("(one|two|readonly)(/[^/]+)?"))throw new FileNotFoundException("Unknown fixture");
        try{File root=new File(getContext().getCacheDir(),"receive-directory-fixture");root.mkdirs();for(String folder:List.of("one","two","readonly"))new File(root,folder).mkdirs();File file=new File(root,id);
            if(!file.getCanonicalPath().startsWith(root.getCanonicalPath()+File.separator))throw new FileNotFoundException("Unsafe fixture");return file;
        }catch(IOException error){throw new FileNotFoundException(error.toString());}
    }
    public Cursor queryRoots(String[] projection){String[] columns=projection==null?new String[]{DocumentsContract.Root.COLUMN_ROOT_ID,DocumentsContract.Root.COLUMN_DOCUMENT_ID,DocumentsContract.Root.COLUMN_TITLE,DocumentsContract.Root.COLUMN_FLAGS}:projection;MatrixCursor cursor=new MatrixCursor(columns);for(String id:List.of("one","two","readonly")){MatrixCursor.RowBuilder row=cursor.newRow();for(String column:columns)row.add(column,switch(column){case DocumentsContract.Root.COLUMN_ROOT_ID,DocumentsContract.Root.COLUMN_DOCUMENT_ID -> id;case DocumentsContract.Root.COLUMN_TITLE -> "Receive "+id;case DocumentsContract.Root.COLUMN_FLAGS -> DocumentsContract.Root.FLAG_SUPPORTS_CREATE;default -> null;});}return cursor;}
    private void row(MatrixCursor cursor,String id)throws FileNotFoundException {
        File file=file(id);if(!file.exists())throw new FileNotFoundException("Deleted fixture");MatrixCursor.RowBuilder row=cursor.newRow();for(String column:cursor.getColumnNames())row.add(column,switch(column){
            case DocumentsContract.Document.COLUMN_DOCUMENT_ID -> id;
            case DocumentsContract.Document.COLUMN_DISPLAY_NAME -> file.getName();
            case DocumentsContract.Document.COLUMN_MIME_TYPE -> file.isDirectory()?DocumentsContract.Document.MIME_TYPE_DIR:"application/octet-stream";
            case DocumentsContract.Document.COLUMN_FLAGS -> file.isDirectory()?(id.equals("readonly")?0:DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE):DocumentsContract.Document.FLAG_SUPPORTS_WRITE|DocumentsContract.Document.FLAG_SUPPORTS_DELETE;
            case DocumentsContract.Document.COLUMN_SIZE -> file.length();default -> null;
        });
    }
    public Cursor queryDocument(String id,String[] projection)throws FileNotFoundException {MatrixCursor cursor=new MatrixCursor(projection==null?COLUMNS:projection);row(cursor,id);return cursor;}
    public Cursor queryChildDocuments(String parent,String[] projection,String order)throws FileNotFoundException {MatrixCursor cursor=new MatrixCursor(projection==null?COLUMNS:projection);File[] files=file(parent).listFiles();if(files!=null)for(File file:files)row(cursor,parent+"/"+file.getName());return cursor;}
    public ParcelFileDescriptor openDocument(String id,String mode,CancellationSignal signal)throws FileNotFoundException {
        if(!mode.equals("r")&&getContext().getSharedPreferences("directory-fixture",0).getBoolean("fail",false))throw new FileNotFoundException("Injected output failure");
        return ParcelFileDescriptor.open(file(id),ParcelFileDescriptor.parseMode(mode));
    }
    public String createDocument(String parent,String mime,String name)throws FileNotFoundException {
        if(!parent.equals("one")&&!parent.equals("two")||name.contains("/")||name.equals(".")||name.equals(".."))throw new FileNotFoundException("Invalid creation");
        try{File selected=new File(file(parent),name);int n=1;while(!selected.createNewFile())selected=new File(file(parent),name+" ("+n+++")");return parent+"/"+selected.getName();}catch(IOException error){throw new FileNotFoundException(error.toString());}
    }
    public void deleteDocument(String id)throws FileNotFoundException {if(!id.contains("/")||!file(id).delete())throw new FileNotFoundException("Cannot delete fixture");}
    public boolean isChildDocument(String parent,String child){return child.startsWith(parent+"/");}
}
