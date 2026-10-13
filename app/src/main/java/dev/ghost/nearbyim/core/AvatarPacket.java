package dev.ghost.nearbyim.core;

import java.io.IOException;

/** Independent avatar records. IDs and receipts never enter the ordinary attachment engine. */
public final class AvatarPacket {
    public static final int CHUNK_BYTES=4096, WINDOW_CHUNKS=4, WINDOW_BYTES=CHUNK_BYTES*WINDOW_CHUNKS;
    public enum Kind { PROFILE_UPDATE, REQUEST, CHUNK, ACK, RESULT, CANCEL }
    public enum Code { OK, CANCELED, SUPERSEDED, TIMEOUT, BUSY, INVALID_IMAGE, STORAGE_ERROR, HASH_MISMATCH }
    public final Kind kind;
    public final AvatarProfile profile;
    public final String transferId,avatarHash;
    public final long revision,offset;
    public final byte[] data;
    public final Code code;

    AvatarPacket(Kind kind,AvatarProfile profile,String transferId,long revision,String hash,long offset,byte[] data,Code code)throws IOException {
        this.kind=kind;this.profile=profile;this.transferId=transferId;this.revision=revision;this.avatarHash=hash;this.offset=offset;
        this.data=data==null?null:data.clone();this.code=code;validate();
    }
    private static AvatarPacket make(Kind kind,AvatarProfile profile,String id,long revision,String hash,long offset,byte[] data,Code code) {
        try{return new AvatarPacket(kind,profile,id,revision,hash,offset,data,code);}catch(IOException invalid){throw new IllegalArgumentException(invalid.getMessage(),invalid);}
    }
    public static AvatarPacket profile(AvatarProfile profile){return make(Kind.PROFILE_UPDATE,profile,"",profile==null?-1:profile.revision,profile==null?null:profile.avatarHash,0,new byte[0],null);}
    public static AvatarPacket request(AvatarProfile profile,String id){return make(Kind.REQUEST,null,id,profile==null?-1:profile.revision,profile==null?null:profile.avatarHash,0,new byte[0],null);}
    public static AvatarPacket chunk(String id,long revision,String hash,long offset,byte[] data){return make(Kind.CHUNK,null,id,revision,hash,offset,data,null);}
    public static AvatarPacket ack(String id,long revision,String hash,long offset){return make(Kind.ACK,null,id,revision,hash,offset,new byte[0],null);}
    public static AvatarPacket result(String id,long revision,String hash,Code code){return make(Kind.RESULT,null,id,revision,hash,0,new byte[0],code);}
    public static AvatarPacket cancel(String id,long revision,String hash,Code code){return make(Kind.CANCEL,null,id,revision,hash,0,new byte[0],code);}
    void validate()throws IOException {
        if(kind==null||transferId==null||avatarHash==null||revision<0||offset<0||offset>AvatarSpec.MAX_BYTES||data==null)throw new IOException("Invalid avatar packet");
        if(kind==Kind.PROFILE_UPDATE){
            if(profile==null||profile.revision!=revision||!profile.avatarHash.equals(avatarHash)||!transferId.isEmpty()||offset!=0||data.length!=0||code!=null)throw new IOException("Invalid avatar profile packet");
            return;
        }
        if(profile!=null||!transferId.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")||!avatarHash.matches("[0-9a-f]{64}"))throw new IOException("Invalid avatar task identity");
        if(kind==Kind.CHUNK){
            if(data.length<1||data.length>CHUNK_BYTES||data.length>AvatarSpec.MAX_BYTES-offset||code!=null)throw new IOException("Invalid avatar chunk");
        }else if(data.length!=0)throw new IOException("Unexpected avatar data");
        if(kind!=Kind.CHUNK&&kind!=Kind.ACK&&offset!=0)throw new IOException("Unexpected avatar offset");
        if(kind==Kind.RESULT){
            if(code!=Code.OK&&code!=Code.BUSY&&code!=Code.INVALID_IMAGE&&code!=Code.STORAGE_ERROR&&code!=Code.HASH_MISMATCH)throw new IOException("Invalid avatar result");
        }else if(kind==Kind.CANCEL){
            if(code!=Code.CANCELED&&code!=Code.SUPERSEDED&&code!=Code.TIMEOUT)throw new IOException("Invalid avatar cancellation");
        }else if(code!=null)throw new IOException("Unexpected avatar code");
    }
}
