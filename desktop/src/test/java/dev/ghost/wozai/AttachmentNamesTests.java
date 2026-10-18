package dev.ghost.wozai;

import dev.ghost.nearbyim.core.*;
import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Original basenames, cheap repeated access, collision isolation and private-cache cleanup. */
public final class AttachmentNamesTests {
    private static int checks;
    public static void main(String[] args)throws Exception {
        Path root=Files.createTempDirectory("attachment-names-");String peer=UUID.randomUUID().toString(),other=UUID.randomUUID().toString();
        byte[] bytes={1,2,3,4};Path first,second,unrelated,source;
        AttachmentRecord original;
        try{
            try(DesktopStore store=new DesktopStore(root)){
                original=received("报告 (最终) 😀.PDF",bytes);source=put(store,peer,original,bytes);
                first=store.attachmentNamedFile(peer,original);
                check(first.getFileName().toString().equals(original.info.name),"Unicode name, spaces, parentheses and extension case are retained");
                check(Files.isSameFile(first,source)&&Arrays.equals(bytes,Files.readAllBytes(first)),"Original-name entry retains the exact payload without another copy");
                check(store.attachmentNamedFile(peer,original).equals(first),"Repeated opens reuse a stable name entry");
                AttachmentRecord duplicate=received(original.info.name,new byte[]{7,8});put(store,peer,duplicate,new byte[]{7,8});second=store.attachmentNamedFile(peer,duplicate);
                check(!first.equals(second)&&first.getFileName().equals(second.getFileName())&&Arrays.equals(Files.readAllBytes(first),bytes),"Same-name transfers keep their basenames without overwriting each other");
                AttachmentRecord reverse=new AttachmentRecord(original.info,true,"delivered",bytes.length);put(store,peer,reverse,bytes);
                check(!store.attachmentNamedFile(peer,reverse).equals(first),"Incoming and outgoing copies with the same ID remain separate");
                AttachmentRecord unsafe=received("../../payload.pdf",bytes);put(store,peer,unsafe,bytes);Path safe=store.attachmentNamedFile(peer,unsafe);
                check(safe.getFileName().toString().equals(AttachmentInfo.safeName(unsafe.info.name))&&safe.normalize().startsWith(root.resolve("attachment-names")),"Remote path separators cannot escape the private name directory");
                AttachmentRecord incomplete=new AttachmentRecord(original.info,false,"transferring",1);
                refused(()->store.attachmentNamedFile(peer,incomplete),"Incomplete content has no external filename entry");
                Files.delete(second);Files.write(second,new byte[]{99});
                refused(()->store.attachmentNamedFile(peer,duplicate),"A conflicting local file is never overwritten");
                check(Arrays.equals(Files.readAllBytes(second),new byte[]{99}),"Name conflict retains the pre-existing content");
                AttachmentRecord separate=received(original.info.name,bytes);put(store,other,separate,bytes);unrelated=store.attachmentNamedFile(other,separate);
                Path external=root.resolve("user-copy.pdf");Files.write(external,new byte[]{42});store.exportLocation(peer,original,external);
                try{
                    Files.delete(second);Files.createSymbolicLink(second,external);
                    refused(()->store.attachmentNamedFile(peer,duplicate),"A symlink cannot replace a private original-name entry");
                    check(Arrays.equals(Files.readAllBytes(external),new byte[]{42}),"Symlink rejection leaves external content unchanged");
                    Files.delete(second);
                }catch(UnsupportedOperationException|FileSystemException unavailable){System.out.println("Attachment name symlink case unavailable on this filesystem");}
            }
            try(DesktopStore restarted=new DesktopStore(root)){
                check(restarted.attachmentNamedFile(peer,original).equals(first)&&Files.isSameFile(first,source),"Restart preserves the original-name entry and retained transfer content");
                restarted.clear(peer);
                check(!Files.exists(first)&&!Files.exists(source),"Clearing history removes internal content and its name entries");
                check(Files.isRegularFile(unrelated)&&Files.isRegularFile(root.resolve("user-copy.pdf")),"Clearing one peer preserves other peers and explicit user exports");
            }
            System.out.println("Attachment original names: "+checks+" checks passed");
        }finally{try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    }
    private static AttachmentRecord received(String name,byte[] bytes)throws Exception{
        AttachmentInfo info=AttachmentInfo.v2(UUID.randomUUID().toString(),name,"application/pdf",bytes.length,AttachmentInfo.hex(MessageDigest.getInstance("SHA-256").digest(bytes)),0);
        return new AttachmentRecord(info,false,"received",bytes.length);
    }
    private static Path put(DesktopStore store,String peer,AttachmentRecord record,byte[] bytes)throws Exception{
        Path path=store.attachmentFile(peer,record.info,record.outgoing);Files.createDirectories(path.getParent());Files.write(path,bytes);store.attachment(peer,record);return path;
    }
    private interface Operation {void run()throws IOException;}
    private static void refused(Operation operation,String message)throws Exception{try{operation.run();throw new AssertionError(message);}catch(IOException expected){checks++;}}
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);checks++;}
}
