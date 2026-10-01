package dev.ghost.nearbyim.core;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.*;
import java.util.regex.Pattern;

/** Both transports use this bounded, versioned binary protocol. */
public final class Protocol {
    public static final int MAX_TEXT_BYTES = 8192;
    private static final int MAGIC = 0x4e494d31, MAX_FRAME = 9216;
    private static final Pattern UUID = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    public static void write(OutputStream output, Frame frame) throws IOException {
        validate(frame);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream payload = new DataOutputStream(bytes);
        payload.writeInt(MAGIC); payload.writeByte(1); payload.writeByte(frame.type);
        payload.writeLong(frame.timestamp); writeString(payload, frame.id); writeString(payload, frame.body);
        DataOutputStream wire = new DataOutputStream(output);
        wire.writeInt(bytes.size()); bytes.writeTo(wire); wire.flush();
    }

    public static Frame read(InputStream input) throws IOException {
        DataInputStream wire = new DataInputStream(input);
        int length = wire.readInt();
        if (length < 22 || length > MAX_FRAME) throw new IOException("Invalid frame length");
        byte[] bytes = new byte[length]; wire.readFully(bytes);
        DataInputStream payload = new DataInputStream(new ByteArrayInputStream(bytes));
        if (payload.readInt() != MAGIC || payload.readUnsignedByte() != 1) throw new IOException("Unsupported protocol");
        int type = payload.readUnsignedByte(); long time = payload.readLong();
        Frame frame = new Frame(type, readString(payload, 64), readString(payload, MAX_TEXT_BYTES), time);
        if (payload.available() != 0) throw new IOException("Trailing frame data");
        validate(frame); return frame;
    }

    private static void validate(Frame frame) throws IOException {
        if (frame == null || frame.id == null || frame.body == null || frame.timestamp < 0) throw new IOException("Invalid frame");
        switch (frame.type) {
            case Frame.HELLO:
                identity(frame.id);
                if (frame.body.trim().isEmpty() || frame.body.codePointCount(0, frame.body.length()) > 32 || utf8(frame.body).length > 128
                        || frame.body.codePoints().anyMatch(Character::isISOControl)) throw new IOException("Invalid nickname");
                break;
            case Frame.TEXT:
                identity(frame.id);
                if (frame.body.trim().isEmpty() || utf8(frame.body).length > MAX_TEXT_BYTES) throw new IOException("Invalid text length");
                break;
            case Frame.ACK: identity(frame.id); empty(frame.body); break;
            case Frame.READY: case Frame.BYE: case Frame.PING: case Frame.PONG: empty(frame.id); empty(frame.body); break;
            default: throw new IOException("Unknown frame type");
        }
    }
    private static void identity(String id) throws IOException { if (!UUID.matcher(id).matches()) throw new IOException("Invalid identity"); }
    private static void empty(String text) throws IOException { if (!text.isEmpty()) throw new IOException("Unexpected payload"); }
    private static byte[] utf8(String text) throws IOException {
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text));
            byte[] bytes = new byte[encoded.remaining()]; encoded.get(bytes); return bytes;
        } catch (CharacterCodingException e) { throw new IOException("Malformed Unicode", e); }
    }
    private static void writeString(DataOutputStream out, String text) throws IOException { byte[] bytes = utf8(text); out.writeInt(bytes.length); out.write(bytes); }
    private static String readString(DataInputStream in, int max) throws IOException {
        int size = in.readInt(); if (size < 0 || size > max || size > in.available()) throw new IOException("Invalid string length");
        byte[] bytes = new byte[size]; in.readFully(bytes);
        try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (CharacterCodingException e) { throw new IOException("Malformed UTF-8", e); }
    }
}
