package dev.ghost.nearbyim.core;

import java.io.*;
import java.nio.file.Path;

/** Raw UTF-16 preserves even an unfinished IME surrogate without Properties escaping. */
public final class DraftStore {
    private static final int MAGIC=0x57445231, FILE_LIMIT=2*TextLimits.DRAFT_BYTES+40;
    private final Path directory;
    public DraftStore(Path directory){this.directory=directory;}
    private Path file(String peer)throws IOException{
        if(peer==null||!peer.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}"))throw new IOException("Invalid draft peer");
        return directory.resolve(peer.toLowerCase(java.util.Locale.ROOT)+".draft");
    }
    /** null means absent; an empty string is a saved, cleared draft. */
    public String load(String peer)throws IOException{
        byte[] data=PrivateMetadata.read(file(peer),FILE_LIMIT);if(data==null)return null;
        DataInputStream input=new DataInputStream(new ByteArrayInputStream(data));
        if(input.readInt()!=MAGIC)throw new IOException("Invalid draft version");
        int units=input.readInt();if(units<0||units>TextLimits.DRAFT_BYTES||input.available()!=2*units)throw new IOException("Invalid draft size");
        char[] text=new char[units];for(int i=0;i<units;i++)text[i]=input.readChar();
        String value=new String(text);if(TextLimits.draftBytes(value)>TextLimits.DRAFT_BYTES)throw new IOException("Draft exceeds budget");return value;
    }
    public void save(String peer,String text)throws IOException{
        Path file=file(peer);
        if(text==null||text.length()>TextLimits.DRAFT_BYTES||TextLimits.draftBytes(text)>TextLimits.DRAFT_BYTES)throw new IOException("Draft exceeds budget");
        ByteArrayOutputStream bytes=new ByteArrayOutputStream(8+text.length()*2);DataOutputStream output=new DataOutputStream(bytes);
        output.writeInt(MAGIC);output.writeInt(text.length());for(int i=0;i<text.length();i++)output.writeChar(text.charAt(i));
        PrivateMetadata.write(file,bytes.toByteArray(),FILE_LIMIT);
    }
}
