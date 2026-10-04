package dev.ghost.nearbyim.core;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
public final class AttachmentTests {
    public static void main(String[] args) throws Exception {
        CoreTests.test("Binary attachment chunks preserve arbitrary bytes and offset", () -> {
            Constructor<Frame> constructor;
            try { constructor = Frame.class.getConstructor(int.class, String.class, String.class, long.class, long.class, byte[].class); }
            catch (NoSuchMethodException e) { throw new AssertionError("Binary attachment frames are not implemented"); }
            byte[] data = new byte[32768]; new Random(5).nextBytes(data);
            Frame chunk = constructor.newInstance(11, CoreTests.A, "", 1L, 32768L, data);
            Frame decoded = Protocol.read(new ByteArrayInputStream(CoreTests.encode(chunk)));
            CoreTests.check(Arrays.equals(data, (byte[])Frame.class.getField("data").get(decoded)), "Binary bytes changed");
            CoreTests.check(Frame.class.getField("offset").getLong(decoded)==32768, "Offset changed");
        });
        CoreTests.test("Signed channel carries a full binary chunk after authenticated negotiation", () -> {
            AuthenticatedChannel a=new AuthenticatedChannel(DeviceIdentity.generate(),new Frame(Frame.HELLO,CoreTests.A,"a",1));
            AuthenticatedChannel b=new AuthenticatedChannel(DeviceIdentity.generate(),new Frame(Frame.HELLO,CoreTests.B,"b",1));
            ByteArrayOutputStream ao=new ByteArrayOutputStream(),bo=new ByteArrayOutputStream();a.writeOffer(ao);b.writeOffer(bo);
            a.readOffer(new ByteArrayInputStream(bo.toByteArray()));b.readOffer(new ByteArrayInputStream(ao.toByteArray()));
            ao.reset();bo.reset();a.writeProof(ao);b.writeProof(bo);a.readProof(new ByteArrayInputStream(bo.toByteArray()));b.readProof(new ByteArrayInputStream(ao.toByteArray()));
            ao.reset();byte[] data=new byte[32768];Arrays.fill(data,(byte)255);
            Frame frame=Frame.class.getConstructor(int.class,String.class,String.class,long.class,long.class,byte[].class).newInstance(11,CoreTests.A,"",1L,0L,data);
            a.write(ao,frame);Frame read=b.read(new ByteArrayInputStream(ao.toByteArray()));
            CoreTests.check(Arrays.equals(data,(byte[])Frame.class.getField("data").get(read)),"Signed chunk changed");
        });
        TransferTests.run();
        System.out.println("Attachments: " + CoreTests.passed + " passed, " + CoreTests.failed + " failed");
        if(CoreTests.failed!=0)System.exit(1);
    }
}
