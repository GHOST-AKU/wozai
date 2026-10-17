package dev.ghost.nearbyim.core;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.regex.Pattern;

/** File identifiers are scoped to an authenticated identity pair and direction. */
public record TransferTaskKey(String localRoot,String remoteRoot,Direction direction,String transferId,String sourceGeneration) {
    public enum Direction { SEND, RECEIVE }
    private static final Pattern ROOT=Pattern.compile("[0-9a-f]{64}"),UUID=Pattern.compile("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}");
    public TransferTaskKey {
        if(localRoot==null||remoteRoot==null||direction==null||transferId==null||sourceGeneration==null||!ROOT.matcher(localRoot).matches()||!ROOT.matcher(remoteRoot).matches()||!UUID.matcher(transferId).matches()||!UUID.matcher(sourceGeneration).matches())throw new IllegalArgumentException("Invalid transfer task identity");
    }
    public String fileName() {
        String canonical="wozai-transfer-task-v2\n"+localRoot+"\n"+remoteRoot+"\n"+direction+"\n"+transferId+"\n"+sourceGeneration;
        try{return AttachmentInfo.hex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.US_ASCII)));}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
}
