package com.assignment.proxy;

import java.io.*;
import java.nio.ByteBuffer;

public final class FramedCodec {
    private FramedCodec() {}

    public static void writeFrame(OutputStream out, byte[] payload) throws IOException {
        out.write(ByteBuffer.allocate(4).putInt(payload.length).array());
        out.write(payload);
        out.flush();
    }

    public static byte[] readFrame(InputStream in) throws IOException {
        byte[] lenBytes = in.readNBytes(4);
        if (lenBytes.length == 0) return null;
        if (lenBytes.length != 4) throw new EOFException("Incomplete frame length");
        int length = ByteBuffer.wrap(lenBytes).getInt();
        if (length < 0 || length > 10_000_000) throw new IOException("Bad frame length: " + length);
        byte[] payload = in.readNBytes(length);
        if (payload.length != length) throw new EOFException("Incomplete frame payload");
        return payload;
    }
}
