package dev.ghost.nearbyim.core;
import java.io.*;
import java.util.*;
/** Small immutable history snapshot. Progress feedback is separate from save receipts. */
public final class AttachmentRecord {
    public final AttachmentInfo info;
    public final boolean outgoing;
    public final String state;
    public final long transferred;
    public AttachmentRecord(AttachmentInfo info,boolean outgoing,String state,long transferred)throws IOException {
        if(!Arrays.asList("preparing","offered","transferring","verifying","awaitingReceipt","received","delivered","rejected","canceled","failed","interrupted","unknown").contains(state)||transferred<0||transferred>info.size)throw new IOException("Invalid attachment state");
        this.info=info;this.outgoing=outgoing;this.state=state;this.transferred=transferred;
    }
    public String stateKey(){return "attachmentState"+Character.toUpperCase(state.charAt(0))+state.substring(1);}
    public boolean active(){return Arrays.asList("preparing","offered","transferring","verifying","awaitingReceipt").contains(state);}
    public AttachmentRecord recovered()throws IOException {return active()?new AttachmentRecord(info,outgoing,state.equals("awaitingReceipt")?"unknown":"interrupted",transferred):this;}
    public String encode()throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();Protocol.write(bytes,info.offer());DataOutputStream out=new DataOutputStream(bytes);
        out.writeBoolean(outgoing);out.writeUTF(state);out.writeLong(transferred);return Base64.getEncoder().encodeToString(bytes.toByteArray());
    }
    public static AttachmentRecord decode(String value)throws IOException {
        if(value==null||value.length()>8192)throw new IOException("Invalid attachment record length");
        try {ByteArrayInputStream bytes=new ByteArrayInputStream(Base64.getDecoder().decode(value));AttachmentInfo info=AttachmentInfo.from(Protocol.read(bytes));DataInputStream in=new DataInputStream(bytes);
            AttachmentRecord record=new AttachmentRecord(info,in.readBoolean(),in.readUTF(),in.readLong());if(in.available()!=0)throw new IOException("Trailing attachment record");return record;
        }catch(IllegalArgumentException e){throw new IOException("Invalid attachment record",e);}
    }
    public boolean equals(Object other){if(!(other instanceof AttachmentRecord))return false;AttachmentRecord r=(AttachmentRecord)other;return outgoing==r.outgoing&&state.equals(r.state)&&transferred==r.transferred&&info.id.equals(r.info.id)&&info.name.equals(r.info.name)&&info.mime.equals(r.info.mime)&&info.hash.equals(r.info.hash)&&info.size==r.info.size&&info.time==r.info.time;}
    public int hashCode(){return Objects.hash(info.id,outgoing,state,transferred);}
}
