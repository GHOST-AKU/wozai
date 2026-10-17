package dev.ghost.nearbyim.core;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Private content-addressed avatars with one independent revision journal per P-256 root. */
public final class AvatarStore {
    private static final int MAGIC=0x57415631, JOURNAL_LIMIT=4096, MAX_ENTRIES=4096;
    // A bounded set of locks also serializes separate stores using the same private cache.
    private static final Object[] LOCKS=new Object[64];
    static {for(int i=0;i<LOCKS.length;i++)LOCKS[i]=new Object();}
    private final Path root,profiles,metadata;
    private final String fingerprint;
    private final Object lock;

    public AvatarStore(Path avatarRoot,String canonicalRootBase64)throws IOException {
        Objects.requireNonNull(avatarRoot);root=avatarRoot.toAbsolutePath().normalize();
        try{Identicon.fromPublicKey(canonicalRootBase64);fingerprint=DeviceIdentity.fingerprint(canonicalRootBase64);}
        catch(GeneralSecurityException invalid){throw new IOException("Invalid avatar owner identity",invalid);}
        profiles=root.resolve("profiles");metadata=profiles.resolve(fingerprint+".bin");
        lock=LOCKS[Math.floorMod(root.toString().hashCode(),LOCKS.length)];
        synchronized(lock){directories();cleanScratch();}
    }
    private void directories()throws IOException {PrivateMetadata.directory(root);PrivateMetadata.directory(profiles);}
    Path directory(){return root;}
    String ownerFingerprint(){return fingerprint;}
    private void cleanScratch()throws IOException {
        int count=0;try(DirectoryStream<Path> files=Files.newDirectoryStream(root,".avatar-*.tmp")){
            for(Path file:files){if(++count>MAX_ENTRIES||Files.isSymbolicLink(file)||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))throw new IOException("Unsafe avatar scratch file");Files.delete(file);}
        }
    }
    private static String hashKey(String hash)throws IOException {if(hash==null||!hash.matches("[0-9a-f]{64}"))throw new IOException("Invalid avatar content hash");return hash;}

    public AvatarSnapshot snapshot()throws IOException {
        synchronized(lock){directories();AvatarProfile profile=load(metadata,fingerprint);byte[] png=profile.avatarHash.isEmpty()?new byte[0]:contentNow(profile.avatarHash);
            if(png.length!=0){AvatarSpec.PngInfo info=AvatarSpec.validatePng(png);if(png.length!=profile.byteLength||info.width()!=profile.width||info.height()!=profile.height)png=new byte[0];}
            return new AvatarSnapshot(profile,png);
        }
    }
    /** Verified shared-cache lookup, with no mutation of any identity's profile. */
    public byte[] content(String avatarHash)throws IOException {hashKey(avatarHash);synchronized(lock){directories();return contentNow(avatarHash);}}
    private byte[] contentNow(String hash)throws IOException {
        Path file=root.resolve(hashKey(hash)+".png");
        if(!Files.exists(file,LinkOption.NOFOLLOW_LINKS))return new byte[0];
        // Missing/corrupt content is recoverable; unsafe path substitution remains an error.
        if(Files.isSymbolicLink(file)||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))throw new IOException("Unsafe avatar content file");
        long length=Files.size(file);if(length<1||length>AvatarSpec.MAX_BYTES)return new byte[0];
        try(InputStream input=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){
            ByteArrayOutputStream output=new ByteArrayOutputStream((int)length);byte[] buffer=new byte[4096];int n;
            while((n=input.read(buffer))!=-1){if(n==0)continue;if(n>AvatarSpec.MAX_BYTES-output.size())return new byte[0];output.write(buffer,0,n);}
            byte[] png=output.toByteArray();if(!sha(png).equals(hash))return new byte[0];
            try{AvatarSpec.validatePng(png);}catch(IOException invalid){return new byte[0];}return png;
        }catch(NoSuchFileException disappeared){return new byte[0];}
    }
    public AvatarSnapshot save(byte[] png)throws IOException {synchronized(lock){checkCanceled();return saveNow(loadCurrent().revision,png);}}
    public AvatarSnapshot save(long expectedRevision,byte[] png)throws IOException {synchronized(lock){checkCanceled();return saveNow(expectedRevision,png);}}
    private AvatarSnapshot saveNow(long expectedRevision,byte[] source)throws IOException {
        AvatarProfile current=loadCurrent();checkRevision(current,expectedRevision);long revision=next(current.revision);
        if(source==null||source.length>AvatarSpec.MAX_BYTES)throw new IOException("Invalid avatar PNG size");byte[] png=source.clone();
        AvatarSpec.PngInfo info=AvatarSpec.validatePng(png);AvatarProfile updated=new AvatarProfile(revision,sha(png),"png",info.width(),info.height(),png.length);
        return commit(updated,png);
    }
    public AvatarSnapshot reset()throws IOException {synchronized(lock){checkCanceled();return resetNow(loadCurrent().revision);}}
    public AvatarSnapshot reset(long expectedRevision)throws IOException {synchronized(lock){checkCanceled();return resetNow(expectedRevision);}}
    private AvatarSnapshot resetNow(long expectedRevision)throws IOException {AvatarProfile current=loadCurrent();checkRevision(current,expectedRevision);return commit(AvatarProfile.defaults(next(current.revision)),new byte[0]);}

    /** Adopt only the bound remote root's profile; this operation does not increment its sender revision. */
    public AvatarSnapshot adopt(long expectedRevision,AvatarProfile updated,byte[] source)throws IOException {
        synchronized(lock){
            checkCanceled();
            AvatarProfile current=loadCurrent();checkRevision(current,expectedRevision);if(updated==null||source==null||source.length>AvatarSpec.MAX_BYTES)throw new IOException("Invalid remote avatar snapshot");
            if(updated.revision<current.revision||updated.revision==current.revision&&!updated.equals(current))throw new IOException("Outdated or conflicting avatar revision");
            byte[] png=source.clone();if(updated.avatarHash.isEmpty()){if(png.length!=0)throw new IOException("Default avatar cannot include PNG bytes");}
            else {AvatarSpec.PngInfo info=AvatarSpec.validatePng(png);if(png.length!=updated.byteLength||info.width()!=updated.width||info.height()!=updated.height||!sha(png).equals(updated.avatarHash))throw new IOException("Remote avatar metadata does not match PNG");}
            return commit(updated,png);
        }
    }
    private AvatarProfile loadCurrent()throws IOException {directories();return load(metadata,fingerprint);}
    private static void checkRevision(AvatarProfile current,long expected)throws IOException {if(expected<0||expected!=current.revision)throw new IOException("Avatar selection is outdated");}
    private static long next(long revision)throws IOException {if(revision==Long.MAX_VALUE)throw new IOException("Avatar revision exhausted");return revision+1;}
    private static void checkCanceled()throws InterruptedIOException {if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Avatar update canceled");}
    private AvatarSnapshot commit(AvatarProfile updated,byte[] png)throws IOException {
        checkCanceled();
        // Validate the returned immutable result before the profile's atomic commit point.
        AvatarSnapshot result=new AvatarSnapshot(updated,png);
        if(!updated.avatarHash.isEmpty()){
            byte[] cached=contentNow(updated.avatarHash);if(!Arrays.equals(cached,png)){ensureRoom(updated.avatarHash,png.length);writeContent(updated.avatarHash,png);}
        }
        PrivateMetadata.write(metadata,encode(updated,fingerprint),JOURNAL_LIMIT,()->Thread.currentThread().isInterrupted());
        // Cleanup cannot turn an already committed profile into a reported failed save.
        try{prune();}catch(IOException deferred){}
        return result;
    }
    private void writeContent(String hash,byte[] png)throws IOException {
        checkCanceled();
        Path file=root.resolve(hash+".png");if(Files.isSymbolicLink(file)||Files.exists(file,LinkOption.NOFOLLOW_LINKS)&&!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))throw new IOException("Unsafe avatar destination");
        Path temporary=Files.createTempFile(root,".avatar-",".tmp");
        try{
            PrivateMetadata.permissions(temporary,false);
            try(FileOutputStream output=new FileOutputStream(temporary.toFile())){output.write(png);checkCanceled();output.getChannel().force(true);}
            checkCanceled();
            Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);TransferCheckpointStore.syncDirectory(root);
        }finally{Files.deleteIfExists(temporary);}
    }
    private static AvatarProfile load(Path file,String expectedFingerprint)throws IOException {
        byte[] bytes=PrivateMetadata.read(file,JOURNAL_LIMIT);if(bytes==null)return AvatarProfile.defaults(0);
        try(DataInputStream input=new DataInputStream(new ByteArrayInputStream(bytes))){
            if(input.readInt()!=MAGIC||input.readInt()!=1)throw new IOException("Unknown avatar profile version");
            long revision=input.readLong();if(!input.readUTF().equals(expectedFingerprint))throw new IOException("Avatar profile identity mismatch");
            AvatarProfile profile=new AvatarProfile(revision,input.readUTF(),input.readUTF(),input.readInt(),input.readInt(),input.readInt());if(input.available()!=0)throw new IOException("Trailing avatar metadata");return profile;
        }catch(IllegalArgumentException invalid){throw new IOException("Invalid avatar profile",invalid);}
    }
    private static byte[] encode(AvatarProfile profile,String fingerprint)throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream output=new DataOutputStream(bytes);output.writeInt(MAGIC);output.writeInt(1);output.writeLong(profile.revision);output.writeUTF(fingerprint);output.writeUTF(profile.avatarHash);output.writeUTF(profile.format);output.writeInt(profile.width);output.writeInt(profile.height);output.writeInt(profile.byteLength);return bytes.toByteArray();
    }
    private Set<String> referenced()throws IOException {
        Set<String> hashes=new HashSet<>();int count=0;
        try(DirectoryStream<Path> entries=Files.newDirectoryStream(profiles,"*.bin")){
            for(Path file:entries){if(++count>MAX_ENTRIES)throw new IOException("Avatar profile count exceeds bounds");String name=file.getFileName().toString();if(!name.matches("[0-9a-f]{64}\\.bin"))throw new IOException("Unsafe avatar profile name");AvatarProfile profile=load(file,name.substring(0,64));if(!profile.avatarHash.isEmpty())hashes.add(profile.avatarHash);}
        }return hashes;
    }
    private List<Path> contents()throws IOException {
        List<Path> files=new ArrayList<>();try(DirectoryStream<Path> entries=Files.newDirectoryStream(root,"*.png")){
            for(Path file:entries){if(files.size()>=MAX_ENTRIES||!file.getFileName().toString().matches("[0-9a-f]{64}\\.png")||Files.isSymbolicLink(file)||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))throw new IOException("Unsafe avatar cache entry");files.add(file);}
        }return files;
    }
    private void ensureRoom(String incoming,int length)throws IOException {
        Set<String> pinned=referenced();long total=0;for(Path file:contents()){
            String hash=file.getFileName().toString().substring(0,64);
            if(!pinned.contains(hash)&&!hash.equals(incoming))Files.delete(file);else if(!hash.equals(incoming))total+=Files.size(file);
        }
        if(total>AvatarSpec.MAX_DISK_BYTES-length)throw new IOException("Avatar cache quota is full");
    }
    private void prune()throws IOException {Set<String> pinned=referenced();for(Path file:contents())if(!pinned.contains(file.getFileName().toString().substring(0,64)))Files.delete(file);}
    private static String sha(byte[] bytes){try{return AttachmentInfo.hex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
}
