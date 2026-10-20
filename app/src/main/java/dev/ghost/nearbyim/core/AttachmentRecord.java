package dev.ghost.nearbyim.core;
import java.io.*;
import java.util.*;
/** Small immutable history snapshot. Progress feedback is separate from save receipts. */
public final class AttachmentRecord {
    public final AttachmentInfo info;
    public final boolean outgoing;
    public final String state;
    public final String route;
    public final long transferred;
    public AttachmentRecord(AttachmentInfo info,boolean outgoing,String state,long transferred)throws IOException {
        this(info,outgoing,state,transferred,"");
    }
    private AttachmentRecord(AttachmentInfo info,boolean outgoing,String state,long transferred,String route)throws IOException {
        if(!validRoute(route))throw new IOException("Invalid attachment route");
        if(!Arrays.asList("queued","preparing","offered","transferring","verifying","checking","paused","sourceUnavailable","awaitingReceipt","received","delivered","rejected","canceled","failed","interrupted","unknown").contains(state)||transferred<0||transferred>info.size)throw new IOException("Invalid attachment state");
        if(info.version==2&&Arrays.asList("received","delivered").contains(state)&&(info.hash==null||transferred!=info.size))throw new IOException("Completed attachment requires a final digest and length");
        this.info=info;this.outgoing=outgoing;this.state=state;this.transferred=transferred;this.route=route;
    }
    private static boolean validRoute(String route){
        if(route==null)return false;if(route.isEmpty())return true;
        String[] parts=route.split(">",-1);return parts.length<=3&&new HashSet<>(Arrays.asList(parts)).size()==parts.length&&Arrays.stream(parts).allMatch(part->Arrays.asList("wifi-direct","lan","bluetooth").contains(part));
    }
    public AttachmentRecord withRoute(String route)throws IOException {
        if(!validRoute(route))throw new IOException("Invalid attachment route");
        return route.isEmpty()||this.route.equals(route)?this:new AttachmentRecord(info,outgoing,state,transferred,route);
    }
    public String stateKey(){return "attachmentState"+Character.toUpperCase(state.charAt(0))+state.substring(1);}
    public boolean active(){return Arrays.asList("queued","preparing","offered","transferring","verifying","checking","awaitingReceipt").contains(state);}
    public boolean resumable(){return info.version==2&&Arrays.asList("paused","sourceUnavailable","interrupted","unknown").contains(state);}
    public boolean mayReplace(AttachmentRecord next) {
        boolean unoffered=outgoing&&info.hash==null&&transferred==0;
        boolean preparing=outgoing&&(state.equals("preparing")||state.equals("queued")||unoffered&&resumable())&&transferred==0&&info.size==0&&next!=null&&(next.state.equals("offered")||next.state.equals("queued"));
        if(next==null||outgoing!=next.outgoing||info.version!=next.info.version||!info.id.equals(next.info.id)||!info.name.equals(next.info.name)||!info.mime.equals(next.info.mime)
                ||!preparing&&info.size!=next.info.size||info.time!=next.info.time||!preparing&&info.hash!=null&&!info.hash.equals(next.info.hash))return false;
        return equals(next)||active()||resumable()&&(unoffered&&Arrays.asList("queued","preparing").contains(next.state)||Arrays.asList("checking","transferring","verifying","awaitingReceipt","received","delivered","paused","sourceUnavailable","canceled","failed","rejected").contains(next.state));
    }
    public AttachmentRecord recovered()throws IOException {return active()?new AttachmentRecord(info,outgoing,info.version==2?"paused":state.equals("awaitingReceipt")?"unknown":"interrupted",transferred,route):this;}
    public String encode()throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
        out.writeInt(3);out.writeInt(info.version);
        if(info.version==2)info.writeV2(out);else Protocol.write(bytes,info.offer());
        out.writeBoolean(outgoing);out.writeUTF(state);out.writeLong(transferred);out.writeUTF(route);
        return "v3:"+Base64.getEncoder().encodeToString(bytes.toByteArray());
    }
    public static AttachmentRecord decode(String value)throws IOException {
        if(value==null||value.length()>8192)throw new IOException("Invalid attachment record length");
        if(value.startsWith("v3:")){
            try{ByteArrayInputStream bytes=new ByteArrayInputStream(Base64.getDecoder().decode(value.substring(3)));DataInputStream in=new DataInputStream(bytes);
                if(in.readInt()!=3)throw new IOException("Unsupported attachment record");int version=in.readInt();
                AttachmentInfo info;if(version==2)info=AttachmentInfo.readV2(in);else if(version==1)info=AttachmentInfo.from(Protocol.read(bytes));else throw new IOException("Unsupported attachment metadata");
                int direction=in.readUnsignedByte();if(direction>1)throw new IOException("Invalid attachment direction");
                AttachmentRecord record=new AttachmentRecord(info,direction==1,in.readUTF(),in.readLong(),in.readUTF());
                if(in.available()!=0)throw new IOException("Trailing attachment record");return record;
            }catch(IllegalArgumentException error){throw new IOException("Invalid attachment record",error);}
        }
        if(value.startsWith("v2:")){
            try{DataInputStream in=new DataInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(value.substring(3))));if(in.readInt()!=2)throw new IOException("Unsupported attachment record");AttachmentInfo info=AttachmentInfo.readV2(in);int direction=in.readUnsignedByte();if(direction>1)throw new IOException("Invalid attachment direction");AttachmentRecord record=new AttachmentRecord(info,direction==1,in.readUTF(),in.readLong());if(in.available()!=0)throw new IOException("Trailing attachment record");return record;}
            catch(IllegalArgumentException error){throw new IOException("Invalid attachment record",error);}
        }
        try {ByteArrayInputStream bytes=new ByteArrayInputStream(Base64.getDecoder().decode(value));AttachmentInfo info=AttachmentInfo.from(Protocol.read(bytes));DataInputStream in=new DataInputStream(bytes);
            AttachmentRecord record=new AttachmentRecord(info,in.readBoolean(),in.readUTF(),in.readLong());if(in.available()!=0)throw new IOException("Trailing attachment record");return record;
        }catch(IllegalArgumentException e){throw new IOException("Invalid attachment record",e);}
    }
    public boolean equals(Object other){if(!(other instanceof AttachmentRecord))return false;AttachmentRecord r=(AttachmentRecord)other;return outgoing==r.outgoing&&route.equals(r.route)&&state.equals(r.state)&&transferred==r.transferred&&info.version==r.info.version&&info.id.equals(r.info.id)&&info.name.equals(r.info.name)&&info.mime.equals(r.info.mime)&&Objects.equals(info.hash,r.info.hash)&&info.size==r.info.size&&info.time==r.info.time;}
    public int hashCode(){return Objects.hash(info.id,outgoing,state,transferred,route);}
}
