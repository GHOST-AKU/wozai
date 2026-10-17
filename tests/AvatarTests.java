package dev.ghost.nearbyim.core;

import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.zip.*;

/** Public identity fixtures and real private-file commits; runs with a 32 MiB heap. */
public final class AvatarTests {
    private static int checks;
    private static Class<?> spec, identicon, profile, snapshot, store;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private interface Work {void run() throws Exception;}
    private static void fails(Work work,String message)throws Exception {
        boolean rejected=false;try{work.run();}catch(IOException|GeneralSecurityException|IllegalArgumentException expected){rejected=true;}
        check(rejected,message);
    }
    private static Object invoke(Object target,String name,Class<?>[] types,Object...args)throws Exception {
        try{return target.getClass().getMethod(name,types).invoke(target,args);}
        catch(InvocationTargetException failure){Throwable cause=failure.getCause();if(cause instanceof Exception e)throw e;if(cause instanceof Error e)throw e;throw failure;}
    }
    private static Object create(Class<?> type,Class<?>[] types,Object...args)throws Exception {
        try{return type.getConstructor(types).newInstance(args);}
        catch(InvocationTargetException failure){Throwable cause=failure.getCause();if(cause instanceof Exception e)throw e;throw failure;}
    }
    private static Object icon(String root)throws Exception {
        try{return identicon.getMethod("fromPublicKey",String.class).invoke(null,root);}
        catch(InvocationTargetException failure){throw (Exception)failure.getCause();}
    }
    private static long revision(Object snap)throws Exception{return (long)profile.getField("revision").get(invoke(snap,"profile",new Class<?>[0]));}
    private static String hash(Object snap)throws Exception{return (String)profile.getField("avatarHash").get(invoke(snap,"profile",new Class<?>[0]));}
    private static byte[] bytes(Object snap)throws Exception{return (byte[])invoke(snap,"png",new Class<?>[0]);}
    private static Object read(Object owner)throws Exception{return invoke(owner,"snapshot",new Class<?>[0]);}
    private static Object save(Object owner,byte[] png)throws Exception{return invoke(owner,"save",new Class<?>[]{byte[].class},(Object)png);}
    private static Object save(Object owner,long expected,byte[] png)throws Exception{return invoke(owner,"save",new Class<?>[]{long.class,byte[].class},expected,png);}
    private static Object reset(Object owner,long expected)throws Exception{return invoke(owner,"reset",new Class<?>[]{long.class},expected);}
    private static Object open(Path path,String root)throws Exception{return create(store,new Class<?>[]{Path.class,String.class},path,root);}
    private static String grid(Object icon)throws Exception {
        StringBuilder result=new StringBuilder();for(int y=0;y<7;y++)for(int x=0;x<4;x++)result.append((boolean)invoke(icon,"filled",new Class<?>[]{int.class,int.class},x,y)?'1':'0');return result.toString();
    }
    private static void chunk(DataOutputStream output,String type,byte[] bytes)throws Exception {
        output.writeInt(bytes.length);byte[] name=type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);output.write(name);output.write(bytes);CRC32 crc=new CRC32();crc.update(name);crc.update(bytes);output.writeInt((int)crc.getValue());
    }
    private static byte[] png(int width,int height,int rgba,boolean animated)throws Exception {
        ByteArrayOutputStream file=new ByteArrayOutputStream();DataOutputStream output=new DataOutputStream(file);output.write(new byte[]{(byte)137,80,78,71,13,10,26,10});
        ByteArrayOutputStream header=new ByteArrayOutputStream();DataOutputStream h=new DataOutputStream(header);h.writeInt(width);h.writeInt(height);h.write(new byte[]{8,6,0,0,0});chunk(output,"IHDR",header.toByteArray());
        if(animated){ByteArrayOutputStream control=new ByteArrayOutputStream();DataOutputStream c=new DataOutputStream(control);c.writeInt(1);c.writeInt(0);chunk(output,"acTL",control.toByteArray());}
        ByteArrayOutputStream encoded=new ByteArrayOutputStream();try(DeflaterOutputStream compressed=new DeflaterOutputStream(encoded)){for(int y=0;y<height;y++){compressed.write(0);for(int x=0;x<width;x++){compressed.write(rgba>>>24);compressed.write(rgba>>>16);compressed.write(rgba>>>8);compressed.write(rgba);}}}chunk(output,"IDAT",encoded.toByteArray());chunk(output,"IEND",new byte[0]);return file.toByteArray();
    }
    private static byte[] invalidIdat()throws Exception {
        byte[] valid=png(1,1,0x12345678,false);ByteArrayOutputStream file=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(file);out.write(valid,0,33);chunk(out,"IDAT",new byte[]{1,2,3,4});chunk(out,"IEND",new byte[0]);return file.toByteArray();
    }
    private static void algorithm()throws Exception {
        Object a=icon(SafetyCodeTests.A),b=icon(SafetyCodeTests.B);
        check((int)a.getClass().getField("version").get(a)==1,"Identicon version is fixed at 1");
        check(AttachmentInfo.hex((byte[])invoke(a,"seed",new Class<?>[0])).equals("9dd4b55e052c1d2f85a3c97621e616fe5b134b796e8e7dbc6556d904f8d2b473"),"Root A golden domain-separated SHA-256 seed");
        check(AttachmentInfo.hex((byte[])invoke(b,"seed",new Class<?>[0])).equals("880c245d5e6b5ae3e67cf46d04427203e352ab930d8bf5b75c048e258987080f"),"Root B golden domain-separated SHA-256 seed");
        check(grid(a).equals("1001110111010100101101010101"),"Root A golden MSB-first 28-cell vector");
        check(grid(b).equals("1000100000001100001001000101"),"Root B golden MSB-first 28-cell vector");
        check((int)invoke(a,"background",new Class<?>[]{boolean.class},false)==0xffd8f0e5,"Root A golden light background");
        check((int)invoke(a,"foreground",new Class<?>[]{boolean.class},false)==0xff167554,"Root A golden light foreground");
        check((int)invoke(a,"background",new Class<?>[]{boolean.class},true)==0xff214c3d,"Root A golden dark background");
        check((int)invoke(a,"foreground",new Class<?>[]{boolean.class},true)==0xff8bdaba,"Root A golden dark foreground");
        check(grid(a).equals(grid(icon(SafetyCodeTests.A))),"Identicon is stable when recreated");check(!grid(a).equals(grid(b)),"New root changes default pattern");
        for(int y=0;y<7;y++)for(int x=0;x<7;x++)check(invoke(a,"filled",new Class<?>[]{int.class,int.class},x,y).equals(invoke(a,"filled",new Class<?>[]{int.class,int.class},6-x,y)),"Grid reflects the first four columns");
        Object neutral=identicon.getMethod("neutral").invoke(null);check(grid(neutral).equals("0".repeat(28)),"Unverified identity is a neutral empty tile");
        Method fromSeed=identicon.getDeclaredMethod("fromSeed",byte[].class);fromSeed.setAccessible(true);byte[] dense=new byte[32];Arrays.fill(dense,(byte)255);
        Object empty=fromSeed.invoke(null,(Object)new byte[32]),full=fromSeed.invoke(null,(Object)dense);check(grid(empty).equals("0000000000000001000000000000"),"Empty seed deterministically fills the center");check(grid(full).equals(grid(empty)),"Dense seed is inverted before deterministic empty handling");
        for(String invalid:new String[]{"", "bad", SafetyCodeTests.A+"\n", "x".repeat(8192)})fails(()->icon(invalid),"Invalid root cannot generate authenticated pattern");
        fails(()->invoke(a,"filled",new Class<?>[]{int.class,int.class},7,0),"Out-of-grid cell is rejected");
    }
    private static void persistence()throws Exception {
        Path root=Files.createTempDirectory("avatars-");try{
            Object a=open(root,SafetyCodeTests.A),b=open(root,SafetyCodeTests.B);byte[] first=png(32,32,0x12345678,false),second=png(48,48,0xabcdef00,false);
            Method directory;try{directory=store.getDeclaredMethod("directory");}catch(NoSuchMethodException missing){throw new AssertionError("Private bounded avatar transfer directory is missing",missing);}directory.setAccessible(true);check(directory.invoke(a).equals(root.toAbsolutePath()),"Transfer scratch remains in the private avatar directory");
            check(revision(read(a))==0&&hash(read(a)).isEmpty(),"Old profiles migrate to initial default without identity changes");
            Object saved=save(a,0,first);check(revision(saved)==1&&Arrays.equals(bytes(saved),first),"First image is durably saved");check(hash(saved).equals(AttachmentInfo.hex(MessageDigest.getInstance("SHA-256").digest(first))),"PNG is addressed by final-byte SHA-256");
            check(Arrays.equals((byte[])invoke(b,"content",new Class<?>[]{String.class},hash(saved)),first),"Verified content-addressed cache is reusable without altering another profile");check(((byte[])invoke(a,"content",new Class<?>[]{String.class},"0".repeat(64))).length==0,"Missing content is an empty cache result");
            check(revision(read(b))==0&&hash(read(b)).isEmpty(),"Device roots have independent local avatar profiles");save(b,0,second);check(Arrays.equals(bytes(read(a)),first),"Saving another root cannot modify own avatar");
            first[0]^=1;check(bytes(saved)[0]==(byte)137,"Save owns immutable source bytes");byte[] returned=bytes(saved);returned[0]=0;check(bytes(saved)[0]==(byte)137,"Snapshot does not expose mutable content");
            check(revision(read(open(root,SafetyCodeTests.A)))==1,"Restart preserves revision and root profile");
            fails(()->save(a,0,second),"Late selection result cannot replace a later revision");fails(()->reset(a,0),"Late reset cannot erase a later revision");
            Object cleared=reset(a,1);check(revision(cleared)==2&&hash(cleared).isEmpty()&&bytes(cleared).length==0,"Restore default is a persisted revisioned tombstone");
            check(revision(read(open(root,SafetyCodeTests.A)))==2,"Default tombstone survives restart");check(Arrays.equals(bytes(read(b)),second),"Reset does not alter another root");
            Object again=save(a,2,second);Path content=root.resolve(hash(again)+".png");byte[] corrupt=Files.readAllBytes(content);corrupt[corrupt.length-1]^=1;Files.write(content,corrupt);Object missing=read(a);
            check(revision(missing)==3&&!hash(missing).isEmpty()&&bytes(missing).length==0,"Corrupt content falls back without losing profile ordering");
            check(((byte[])invoke(a,"content",new Class<?>[]{String.class},hash(again))).length==0,"Corrupt cached content is never reused");fails(()->invoke(a,"content",new Class<?>[]{String.class},"../outside"),"Content keys cannot escape private cache");
            Files.delete(content);check(revision(read(a))==3&&bytes(read(a)).length==0,"Missing content is recoverable and retains metadata");
            save(a,3,png(16,16,0xaabbccdd,false));long before=revision(read(a));byte[] old=bytes(read(a));
            Path metadata=root.resolve("profiles").resolve(DeviceIdentity.fingerprint(SafetyCodeTests.A)+".bin");Path backup=metadata.resolveSibling("saved.bin");Files.move(metadata,backup);Files.createDirectory(metadata);
            fails(()->save(a,png(20,20,0x11111111,false)),"Metadata failure prevents a successful save");Files.delete(metadata);Files.move(backup,metadata);check(revision(read(a))==before&&Arrays.equals(bytes(read(a)),old),"Failed config commit preserves the old avatar");
            for(byte[] invalid:new byte[][]{new byte[0],new byte[128*1024+1],png(257,257,0,false),png(2,3,0,false),png(1,1,0,true),invalidIdat()})fails(()->save(a,invalid),"Invalid, excessive, animated, non-square or corrupt PNG is rejected");
            byte[] badCrc=png(1,1,0,false);badCrc[29]^=1;fails(()->save(a,badCrc),"PNG chunk CRC must verify");
            byte[] trailing=Arrays.copyOf(png(1,1,0,false),75);fails(()->save(a,trailing),"Trailing PNG bytes are rejected");check(revision(read(a))==before,"Rejected images never advance configuration");
            Path profileFile=root.resolve("profiles").resolve(DeviceIdentity.fingerprint(SafetyCodeTests.A)+".bin");byte[] journal=PrivateMetadata.read(profileFile,4096);java.nio.ByteBuffer.wrap(journal).putLong(8,Long.MAX_VALUE);PrivateMetadata.write(profileFile,journal,4096);
            fails(()->save(a,png(1,1,0,false)),"Revision overflow cannot wrap");fails(()->reset(a,Long.MAX_VALUE),"Default reset cannot wrap revision");check(revision(read(a))==Long.MAX_VALUE,"Overflow preserves old configuration");
        }finally{try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    }
    private static void concurrentCas()throws Exception {
        Path root=Files.createTempDirectory("avatar-cas-");ExecutorService work=Executors.newFixedThreadPool(2);try{
            Object first=open(root,SafetyCodeTests.A),second=open(root,SafetyCodeTests.A);byte[] png=png(1,1,0x12345600,false);CountDownLatch ready=new CountDownLatch(1);
            Callable<Boolean> one=()->{ready.await();try{save(first,0,png);return true;}catch(IOException stale){return false;}};Callable<Boolean> two=()->{ready.await();try{save(second,0,png);return true;}catch(IOException stale){return false;}};
            Future<Boolean> a=work.submit(one),b=work.submit(two);ready.countDown();check(a.get()!=b.get(),"Concurrent stores sharing an identity accept exactly one revision-zero save");check(revision(read(first))==1,"CAS serializes across store instances");
        }finally{work.shutdownNow();try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    }
    private static void remoteProfiles()throws Exception {
        Path root=Files.createTempDirectory("avatar-remote-");try{
            Object own=open(root,SafetyCodeTests.A),remote=open(root,SafetyCodeTests.B);byte[] png=png(32,32,0x11223344,false);String hash=AttachmentInfo.hex(MessageDigest.getInstance("SHA-256").digest(png));
            Object update=create(profile,new Class<?>[]{long.class,String.class,String.class,int.class,int.class,int.class},5L,hash,"png",32,32,png.length);
            Object saved=invoke(remote,"adopt",new Class<?>[]{long.class,profile,byte[].class},0L,update,png);check(revision(saved)==5&&Arrays.equals(bytes(saved),png),"Remote adoption preserves exact sender revision");
            check(revision(read(own))==0&&hash(read(own)).isEmpty(),"Contact avatar never changes this device's own avatar");
            check(revision(invoke(remote,"adopt",new Class<?>[]{long.class,profile,byte[].class},5L,update,png))==5,"Identical remote revision is idempotent");
            Object old=create(profile,new Class<?>[]{long.class,String.class,String.class,int.class,int.class,int.class},4L,"","",0,0,0);fails(()->invoke(remote,"adopt",new Class<?>[]{long.class,profile,byte[].class},5L,old,new byte[0]),"Old remote revision cannot revive outdated state");
            Object conflict=create(profile,new Class<?>[]{long.class,String.class,String.class,int.class,int.class,int.class},5L,"","",0,0,0);fails(()->invoke(remote,"adopt",new Class<?>[]{long.class,profile,byte[].class},5L,conflict,new byte[0]),"Same revision with conflicting content is rejected");
            Object defaults=create(profile,new Class<?>[]{long.class,String.class,String.class,int.class,int.class,int.class},6L,"","",0,0,0);Object reset=invoke(remote,"adopt",new Class<?>[]{long.class,profile,byte[].class},5L,defaults,new byte[0]);check(revision(reset)==6&&hash(reset).isEmpty(),"Remote reset tombstone uses sender revision");
            fails(()->invoke(remote,"adopt",new Class<?>[]{long.class,profile,byte[].class},5L,update,png),"Stale remote commit cannot overwrite reset");
        }finally{try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    }
    private static void paths()throws Exception {
        Path root=Files.createTempDirectory("avatar-links-");try{
            Path outside=root.resolve("outside");Files.createDirectory(outside);Path linked=root.resolve("linked");try{Files.createSymbolicLink(linked,outside);}catch(UnsupportedOperationException|FileSystemException unsupported){return;}
            fails(()->open(linked,SafetyCodeTests.A),"Symlink avatar root is rejected");Object local=open(root.resolve("private"),SafetyCodeTests.A);byte[] png=png(1,1,0,false);String hash=AttachmentInfo.hex(MessageDigest.getInstance("SHA-256").digest(png));Path target=root.resolve("outside.png");Files.write(target,new byte[]{9});Files.createSymbolicLink(root.resolve("private").resolve(hash+".png"),target);
            fails(()->save(local,png),"Content symlink cannot overwrite an external file");check(Arrays.equals(Files.readAllBytes(target),new byte[]{9}),"Unsafe avatar path does not modify external bytes");
        }finally{try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    }
    private static void boundedSnapshots()throws Exception {
        Object defaults=create(profile,new Class<?>[]{long.class,String.class,String.class,int.class,int.class,int.class},0L,"","",0,0,0);
        fails(()->create(snapshot,new Class<?>[]{profile,byte[].class},defaults,new byte[AvatarSpec.MAX_BYTES+1]),"Immutable snapshots cannot allocate oversized avatar copies");
        fails(()->create(snapshot,new Class<?>[]{profile,byte[].class},defaults,new byte[]{1}),"Default snapshot cannot carry unrelated custom bytes");
        byte[] png=png(1,1,0,false);Object custom=create(profile,new Class<?>[]{long.class,String.class,String.class,int.class,int.class,int.class},1L,"0".repeat(64),"png",1,1,png.length);
        fails(()->create(snapshot,new Class<?>[]{profile,byte[].class},custom,png),"Snapshot bytes must match their promised hash");
    }
    private static void orphanCleanup()throws Exception {
        Path root=Files.createTempDirectory("avatar-orphans-");try{
            Files.write(root.resolve(".avatar-abandoned.tmp"),new byte[]{1,2,3});Object a=open(root,SafetyCodeTests.A);check(!Files.exists(root.resolve(".avatar-abandoned.tmp")),"Crash-left atomic content scratch is reclaimed at store open");
            byte[] png=png(1,1,0xff110022,false);Object committed=save(a,png);Path pinned=root.resolve(hash(committed)+".png");byte[] block=new byte[AvatarSpec.MAX_BYTES];
            for(int i=0;i<65;i++)Files.write(root.resolve(String.format(Locale.ROOT,"%064x",i)+".png"),block);
            save(a,1,png(2,2,0x33ff0011,false));check(!Files.exists(pinned),"Superseded unreferenced image is reclaimed after successful replacement");
            long bytes=0;int files=0;try(var entries=Files.newDirectoryStream(root,"*.png")){for(Path file:entries){bytes+=Files.size(file);files++;}}
            check(bytes<=AvatarSpec.MAX_DISK_BYTES&&files==1,"Orphan-heavy cache is reclaimed within disk quota");
        }finally{try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    }
    private static void cancellation()throws Exception {
        Path root=Files.createTempDirectory("avatar-cancel-");try{
            Object a=open(root,SafetyCodeTests.A);byte[] png=png(1,1,0xff112233,false);Object first=save(a,0,png);
            Thread.currentThread().interrupt();try{fails(()->reset(a,1),"Canceled reset cannot persist a late default state");}finally{Thread.interrupted();}
            check(revision(read(a))==1&&hash(read(a)).equals(hash(first)),"Canceled reset preserves the previous avatar and revision");
            Thread.currentThread().interrupt();try{fails(()->save(a,1,png),"Canceled save cannot persist late processed pixels");}finally{Thread.interrupted();}
            check(revision(read(a))==1,"Canceled save cannot advance revision");
            Object defaults=create(profile,new Class<?>[]{long.class,String.class,String.class,int.class,int.class,int.class},2L,"","",0,0,0);
            Thread.currentThread().interrupt();try{fails(()->invoke(a,"adopt",new Class<?>[]{long.class,profile,byte[].class},1L,defaults,new byte[0]),"Canceled remote default cannot be committed");}finally{Thread.interrupted();}
            check(revision(read(a))==1,"Canceled remote update preserves ordering");
            reset(a,1);fails(()->save(a,1,png),"Cancellation protections retain stale-revision rejection");
            Method owner;try{owner=store.getDeclaredMethod("ownerFingerprint");}catch(NoSuchMethodException missing){throw new AssertionError("Bound avatar owner fingerprint is missing",missing);}owner.setAccessible(true);
            check(owner.invoke(a).equals(DeviceIdentity.fingerprint(SafetyCodeTests.A)),"Store exposes its canonical bound root for sync namespace checks");
        }finally{Thread.interrupted();try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    }
    private static void metadataWrite(Path file,byte[] bytes,BooleanSupplier canceled)throws Exception {
        Method method;try{method=PrivateMetadata.class.getMethod("write",Path.class,byte[].class,int.class,BooleanSupplier.class);}catch(NoSuchMethodException missing){throw new AssertionError("Cancellation-aware private journal commit is missing",missing);}
        try{method.invoke(null,file,bytes,4096,canceled);}catch(InvocationTargetException failure){throw (Exception)failure.getCause();}
    }
    private static void canceledJournal()throws Exception {
        Path root=Files.createTempDirectory("avatar-journal-cancel-");try{
            Path journal=root.resolve("profile.bin");byte[] previous=new byte[]{1,2,3},updated=new byte[]{4,5,6,7};PrivateMetadata.write(journal,previous,4096);
            fails(()->metadataWrite(journal,updated,()->true),"Already canceled journal rejects mutation");check(Arrays.equals(PrivateMetadata.read(journal,4096),previous),"Early cancellation preserves committed journal");
            AtomicInteger completedChecks=new AtomicInteger();BooleanSupplier beforeRename=()->{
                try(var files=Files.newDirectoryStream(root,".journal-*.tmp")){for(Path file:files)if(Files.size(file)==updated.length+32)return completedChecks.incrementAndGet()>=2;}
                catch(IOException failure){throw new UncheckedIOException(failure);}return false;
            };
            fails(()->metadataWrite(journal,updated,beforeRename),"Cancellation after completed temp write prevents atomic rename");check(Arrays.equals(PrivateMetadata.read(journal,4096),previous),"Late cancellation preserves the old profile reference");
            try(var files=Files.newDirectoryStream(root,".journal-*.tmp")){check(!files.iterator().hasNext(),"Canceled journal scratch is reclaimed");}
            metadataWrite(journal,updated,()->false);check(Arrays.equals(PrivateMetadata.read(journal,4096),updated),"Uncanceled guarded journal commits normally");
            PrivateMetadata.write(journal,previous,4096);check(Arrays.equals(PrivateMetadata.read(journal,4096),previous),"Existing three-argument journal behavior remains unchanged");
        }finally{try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    }
    public static void main(String[] args)throws Exception {
        try{spec=Class.forName("dev.ghost.nearbyim.core.AvatarSpec");identicon=Class.forName("dev.ghost.nearbyim.core.Identicon");profile=Class.forName("dev.ghost.nearbyim.core.AvatarProfile");snapshot=Class.forName("dev.ghost.nearbyim.core.AvatarSnapshot");store=Class.forName("dev.ghost.nearbyim.core.AvatarStore");}
        catch(ClassNotFoundException missing){throw new AssertionError("Shared avatar algorithm and persistence are missing",missing);}
        check(spec.getField("MAX_EDGE").getInt(null)==256&&spec.getField("MAX_BYTES").getInt(null)==128*1024,"Shared PNG output limits are strict");algorithm();persistence();concurrentCas();remoteProfiles();paths();boundedSnapshots();orphanCleanup();cancellation();canceledJournal();
        System.out.println("AvatarTests: "+checks+" golden-vector, independent-profile, PNG, atomic-save, CAS and path checks passed");
    }
}
