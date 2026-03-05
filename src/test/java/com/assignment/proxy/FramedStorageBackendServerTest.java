package com.assignment.proxy;

import com.assignment.proxy.HttpModels.HttpRequest;
import com.assignment.proxy.HttpModels.HttpResponse;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FramedStorageBackendServerTest {

    @Test
    void putGetDeleteLifecycleWorks() throws Exception {
        FramedStorageBackendServer server = new FramedStorageBackendServer(9000);
        Method process = FramedStorageBackendServer.class.getDeclaredMethod("process", HttpRequest.class);
        process.setAccessible(true);

        HttpResponse put = (HttpResponse) process.invoke(server, request("PUT", "/kv/user1", "hello"));
        assertEquals(200, put.statusCode());

        HttpResponse get = (HttpResponse) process.invoke(server, request("GET", "/kv/user1", ""));
        assertEquals(200, get.statusCode());
        assertEquals("hello", new String(get.body(), StandardCharsets.UTF_8));

        HttpResponse delete = (HttpResponse) process.invoke(server, request("DELETE", "/kv/user1", ""));
        assertEquals(200, delete.statusCode());

        HttpResponse missing = (HttpResponse) process.invoke(server, request("GET", "/kv/user1", ""));
        assertEquals(404, missing.statusCode());
    }

    private static HttpRequest request(String method, String target, String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        headers.put("Host", "localhost");
        headers.put("Content-Length", String.valueOf(bytes.length));
        return new HttpRequest(method, target, "HTTP/1.1", headers, bytes);
    }
}
