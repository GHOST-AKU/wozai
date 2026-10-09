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
        if(!Arrays.asList("preparing","offered","transferring","verifying","checking","paused","awaitingReceipt","received","delivered","rejected","canceled","failed","interrupted","unknown").contains(state)||transferred<0||transferred>info.size)throw new IOException("Invalid attachment state");
        if(info.version==2&&Arrays.asList("received","delivered").contains(state)&&(info.hash==null||transferred!=info.size))throw new IOException("Completed attachment requires a final digest and length");
        this.info=info;this.outgoing=outgoing;this.state=state;this.transferred=transferred;
    }
    public String stateKey(){return "attachmentState"+Character.toUpperCase(state.charAt(0))+state.substring(1);}
    public boolean active(){return Arrays.asList("preparing","offered","transferring","verifying","checking","awaitingReceipt").contains(state);}
    public boolean resumable(){return info.version==2&&Arrays.asList("paused","interrupted","unknown").contains(state);}
    public boolean mayReplace(AttachmentRecord next) {
        boolean preparing=info.version==1&&outgoing&&state.equals("preparing")&&transferred==0&&info.size==0&&next!=null&&next.state.equals("offered");
        if(next==null||outgoing!=next.outgoing||info.version!=next.info.version||!info.id.equals(next.info.id)||!info.name.equals(next.info.name)||!info.mime.equals(next.info.mime)
                ||!preparing&&info.size!=next.info.size||info.time!=next.info.time||!preparing&&info.hash!=null&&!info.hash.equals(next.info.hash))return false;
        return equals(next)||active()||resumable()&&Arrays.asList("checking","transferring","verifying","awaitingReceipt","received","delivered","paused","canceled","failed","rejected").contains(next.state);
    }
    public AttachmentRecord recovered()throws IOException {return active()?new AttachmentRecord(info,outgoing,info.version==2?"paused":state.equals("awaitingReceipt")?"unknown":"interrupted",transferred):this;}
    public String encode()throws IOException {
        if(info.version==2){ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);out.writeInt(2);info.writeV2(out);out.writeBoolean(outgoing);out.writeUTF(state);out.writeLong(transferred);return "v2:"+Base64.getEncoder().encodeToString(bytes.toByteArray());}
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();Protocol.write(bytes,info.offer());DataOutputStream out=new DataOutputStream(bytes);
        out.writeBoolean(outgoing);out.writeUTF(state);out.writeLong(transferred);return Base64.getEncoder().encodeToString(bytes.toByteArray());
    }
    public static AttachmentRecord decode(String value)throws IOException {
        if(value==null||value.length()>8192)throw new IOException("Invalid attachment record length");
        if(value.startsWith("v2:")){
            try{DataInputStream in=new DataInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(value.substring(3))));if(in.readInt()!=2)throw new IOException("Unsupported attachment record");AttachmentInfo info=AttachmentInfo.readV2(in);int direction=in.readUnsignedByte();if(direction>1)throw new IOException("Invalid attachment direction");AttachmentRecord record=new AttachmentRecord(info,direction==1,in.readUTF(),in.readLong());if(in.available()!=0)throw new IOException("Trailing attachment record");return record;}
            catch(IllegalArgumentException error){throw new IOException("Invalid attachment record",error);}
        }
        try {ByteArrayInputStream bytes=new ByteArrayInputStream(Base64.getDecoder().decode(value));AttachmentInfo info=AttachmentInfo.from(Protocol.read(bytes));DataInputStream in=new DataInputStream(bytes);
            AttachmentRecord record=new AttachmentRecord(info,in.readBoolean(),in.readUTF(),in.readLong());if(in.available()!=0)throw new IOException("Trailing attachment record");return record;
        }catch(IllegalArgumentException e){throw new IOException("Invalid attachment record",e);}
    }
    public boolean equals(Object other){if(!(other instanceof AttachmentRecord))return false;AttachmentRecord r=(AttachmentRecord)other;return outgoing==r.outgoing&&state.equals(r.state)&&transferred==r.transferred&&info.version==r.info.version&&info.id.equals(r.info.id)&&info.name.equals(r.info.name)&&info.mime.equals(r.info.mime)&&Objects.equals(info.hash,r.info.hash)&&info.size==r.info.size&&info.time==r.info.time;}
    public int hashCode(){return Objects.hash(info.id,outgoing,state,transferred);}
}
