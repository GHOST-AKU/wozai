package dev.ghost.nearbyim.core;

import java.nio.file.*;
import java.util.*;

/** The benchmark must verify bytes and fail closed before publishing a report. */
public final class AttachmentBenchmarkTests {
    public static void main(String[] args) throws Exception {
        Path root=Files.createTempDirectory("benchmark-contract-");
        try {
            Path report=root.resolve("zero.json");
            AttachmentTransferBenchmark.main(new String[]{"--mode","raw-tcp","--size","0","--rounds","1","--output",report.toString(),"--environment-kind","virtual"});
            String json=Files.readString(report);
            check(json.contains("\"verified\":true"),"Zero-byte result is not verified");
            check(json.contains("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),"Incorrect empty digest");
            for(String key:List.of("prepare_ns","first_byte_ns","transfer_ns","final_commit_ns","receipt_ns","source_read_mib_s","sync_write_mib_s","environment_kind"))
                check(json.contains("\""+key+"\""),"Missing phase/baseline: "+key);
            Path v2=root.resolve("v2.json");
            AttachmentTransferBenchmark.main(new String[]{"--mode","file-v2","--size","0","--rounds","1","--output",v2.toString(),"--work-dir",root.resolve("data").toString()});
            check(Files.readString(v2).contains("\"verified\":true"),"File v2 result was not verified");
            check(Files.readString(v2).contains("\"file_store_type\""),"Filesystem identity missing");
            Path encrypted=root.resolve("noise.json");
            AttachmentTransferBenchmark.main(new String[]{"--mode","noise-v4","--size","0","--rounds","1","--output",encrypted.toString(),"--work-dir",root.resolve("encrypted-data").toString()});
            check(Files.readString(encrypted).contains("\"verified\":true"),"Encrypted transfer result was not verified");
            for(String mode:List.of("invalid")) {
                Path missing=root.resolve(mode+".json");
                rejects(()->AttachmentTransferBenchmark.main(new String[]{"--mode",mode,"--size","0","--output",missing.toString()}));
                check(!Files.exists(missing),"Unsupported mode published a report");
            }
            for(String size:List.of("-1","10737418241","9223372036854775807"))
                rejects(()->AttachmentTransferBenchmark.main(new String[]{"--size",size,"--output",root.resolve("invalid-size.json").toString()}));
            byte[] expected=new byte[32];
            rejects(()->AttachmentTransferBenchmark.requireDigest(expected,new byte[32],1,0));
            expected[0]=1;
            rejects(()->AttachmentTransferBenchmark.requireDigest(expected,new byte[32],0,0));
            System.out.println("AttachmentBenchmarkTests: report phases, empty transfer, invalid modes/ranges and corrupt results passed");
        } finally {try(var paths=Files.walk(root)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
    }
    private interface Action {void run() throws Exception;}
    private static void rejects(Action action) throws Exception {try{action.run();}catch(java.io.IOException|IllegalArgumentException expected){return;}throw new AssertionError("Expected rejection");}
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
