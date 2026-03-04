package com.assignment.proxy;

import com.assignment.proxy.HttpModels.HttpRequest;
import com.assignment.proxy.HttpModels.HttpResponse;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.*;

class HttpModelsTest {

    @Test
    void requestRoundTripPreservesFields() throws Exception {
        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        headers.put("Host", "localhost");
        headers.put("Content-Length", "5");
        HttpRequest request = new HttpRequest("POST", "/items", "HTTP/1.1", headers, "hello".getBytes(StandardCharsets.UTF_8));

        HttpRequest parsed = HttpModels.readRequest(new ByteArrayInputStream(request.toBytes()));

        assertNotNull(parsed);
        assertEquals("POST", parsed.method());
        assertEquals("/items", parsed.target());
        assertEquals("HTTP/1.1", parsed.version());
        assertEquals("localhost", parsed.headers().get("Host"));
        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), parsed.body());
    }

    @Test
    void responseRoundTripPreservesStatusAndBody() throws Exception {
        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Length", "2");
        HttpResponse response = new HttpResponse("HTTP/1.1", 201, "Created", headers, "ok".getBytes(StandardCharsets.UTF_8));

        HttpResponse parsed = HttpModels.readResponse(new ByteArrayInputStream(response.toBytes()));

        assertNotNull(parsed);
        assertEquals(201, parsed.statusCode());
        assertEquals("Created", parsed.reason());
        assertEquals("2", parsed.headers().get("Content-Length"));
        assertArrayEquals("ok".getBytes(StandardCharsets.UTF_8), parsed.body());
    }

    @Test
    void readLineFailsOnUnexpectedEof() {
        ByteArrayInputStream in = new ByteArrayInputStream("abc".getBytes(StandardCharsets.US_ASCII));
        assertThrows(EOFException.class, () -> HttpModels.readLine(in));
    }
}
