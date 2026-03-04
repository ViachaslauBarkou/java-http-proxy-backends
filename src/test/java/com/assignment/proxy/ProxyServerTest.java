package com.assignment.proxy;

import com.assignment.proxy.HttpModels.HttpRequest;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProxyServerTest {

    @Test
    void normalizeAddsConnectionAndContentLength() throws Exception {
        ProxyServer server = new ProxyServer(8080, List.of(new ProxyServer.BackendTarget("127.0.0.1", 9001, ProxyServer.BackendType.PLAIN, "api-1")));
        Method normalize = ProxyServer.class.getDeclaredMethod("normalize", HttpRequest.class);
        normalize.setAccessible(true);

        HttpRequest req = new HttpRequest("GET", "/x", "HTTP/1.1", new LinkedHashMap<>(), "abc".getBytes());
        HttpRequest normalized = (HttpRequest) normalize.invoke(server, req);

        assertEquals("keep-alive", normalized.headers().get("Connection"));
        assertEquals("3", normalized.headers().get("Content-Length"));
    }
}
