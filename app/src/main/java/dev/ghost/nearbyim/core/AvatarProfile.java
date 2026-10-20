package dev.ghost.nearbyim.core;

import java.util.Objects;

/** Immutable identity-local metadata; revision zero is the migration default. */
public final class AvatarProfile {
    public final long revision;
    public final String avatarHash,format;
    public final int width,height,byteLength;
    public AvatarProfile(long revision,String avatarHash,String format,int width,int height,int byteLength){
        if(revision<0||avatarHash==null||format==null)throw new IllegalArgumentException("Invalid avatar profile");
        if(avatarHash.isEmpty()){
            if(!format.isEmpty()||width!=0||height!=0||byteLength!=0)throw new IllegalArgumentException("Invalid default avatar metadata");
        }else if(!avatarHash.matches("[0-9a-f]{64}")||!format.equals("png")||width<1||width>AvatarSpec.MAX_EDGE||height!=width||byteLength<1||byteLength>AvatarSpec.MAX_BYTES)
            throw new IllegalArgumentException("Invalid custom avatar metadata");
        this.revision=revision;this.avatarHash=avatarHash;this.format=format;this.width=width;this.height=height;this.byteLength=byteLength;
    }
    public static AvatarProfile defaults(long revision){return new AvatarProfile(revision,"","",0,0,0);}
    @Override public boolean equals(Object other){return other instanceof AvatarProfile p&&revision==p.revision&&avatarHash.equals(p.avatarHash)&&format.equals(p.format)&&width==p.width&&height==p.height&&byteLength==p.byteLength;}
    @Override public int hashCode(){return Objects.hash(revision,avatarHash,format,width,height,byteLength);}
}
