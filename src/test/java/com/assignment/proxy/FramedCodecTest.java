package com.assignment.proxy;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

class FramedCodecTest {

    @Test
    void writeThenReadFrameRoundTrip() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] payload = "payload".getBytes();

        FramedCodec.writeFrame(out, payload);
        byte[] read = FramedCodec.readFrame(new ByteArrayInputStream(out.toByteArray()));

        assertArrayEquals(payload, read);
    }

    @Test
    void readFrameRejectsNegativeLength() {
        byte[] len = ByteBuffer.allocate(4).putInt(-1).array();
        ByteArrayInputStream in = new ByteArrayInputStream(len);

        assertThrows(IOException.class, () -> FramedCodec.readFrame(in));
    }

    @Test
    void readFrameReturnsNullOnCleanEof() throws Exception {
        assertNull(FramedCodec.readFrame(new ByteArrayInputStream(new byte[0])));
    }
}
