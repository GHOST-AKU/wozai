package dev.ghost.nearbyim.core;

import java.io.IOException;
import java.security.*;
import java.util.Objects;

/** A saved reference plus available immutable PNG bytes; unavailable content is an empty array. */
public final class AvatarSnapshot {
    private final AvatarProfile profile;
    private final byte[] png;
    public AvatarSnapshot(AvatarProfile profile,byte[] png){
        this.profile=Objects.requireNonNull(profile);Objects.requireNonNull(png);
        if(png.length>AvatarSpec.MAX_BYTES||png.length!=0&&(profile.avatarHash.isEmpty()||png.length!=profile.byteLength))throw new IllegalArgumentException("Invalid avatar snapshot size");
        this.png=png.clone();
        if(this.png.length!=0){
            try{
                AvatarSpec.PngInfo info=AvatarSpec.validatePng(this.png);
                if(info.width()!=profile.width||info.height()!=profile.height||!AttachmentInfo.hex(MessageDigest.getInstance("SHA-256").digest(this.png)).equals(profile.avatarHash))throw new IllegalArgumentException("Avatar snapshot does not match metadata");
            }catch(IOException invalid){throw new IllegalArgumentException("Invalid avatar snapshot PNG",invalid);}
            catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
        }
    }
    public AvatarProfile profile(){return profile;}
    public byte[] png(){return png.clone();}
}
