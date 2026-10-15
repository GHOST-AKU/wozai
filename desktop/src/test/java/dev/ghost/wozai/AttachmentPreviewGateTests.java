package dev.ghost.wozai;

/** Preview policy must preserve the 500ms requirement and all correctness guards. */
public final class AttachmentPreviewGateTests {
    public static void main(String[] args)throws Exception {
        String old=System.getProperty("wozai.test.previewRelease"),report=System.getProperty("wozai.test.acceptanceReport");
        java.io.PrintStream errors=System.err;
        try{
            System.clearProperty("wozai.test.previewRelease");System.clearProperty("wozai.test.acceptanceReport");
            AttachmentStoreTests.checkTextAckPerformance(20,500);
            boolean refused=false;try{AttachmentStoreTests.checkTextAckPerformance(20,510.847299);}catch(AssertionError expected){refused=true;}
            if(!refused)throw new AssertionError("Ordinary CI silently relaxed the 500ms gate");
            System.setProperty("wozai.test.previewRelease","true");
            var warning=new java.io.ByteArrayOutputStream();System.setErr(new java.io.PrintStream(warning));
            AttachmentStoreTests.checkTextAckPerformance(20,510.847299);
            if(!warning.toString(java.nio.charset.StandardCharsets.UTF_8).contains("PREVIEW_QUALITY_GATE_FAILED"))throw new AssertionError("Preview lost the performance failure evidence");
            refused=false;try{AttachmentStoreTests.checkTextAckPerformance(15,10);}catch(AssertionError expected){refused=true;}
            if(!refused)throw new AssertionError("Preview bypassed the real file/text overlap guard");
        }finally{
            System.setErr(errors);
            if(old==null)System.clearProperty("wozai.test.previewRelease");else System.setProperty("wozai.test.previewRelease",old);
            if(report==null)System.clearProperty("wozai.test.acceptanceReport");else System.setProperty("wozai.test.acceptanceReport",report);
        }
        System.out.println("Preview acceptance: default 500ms failure, visible preview warning and strict overlap guard passed");
    }
}
