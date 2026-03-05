package com.assignment.proxy;

import com.assignment.proxy.HttpModels.HttpRequest;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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

    @Test
    void chooseBackendRoutesKvRequestsToStorage() throws Exception {
        ProxyServer server = new ProxyServer(8080, List.of(
                new ProxyServer.BackendTarget("127.0.0.1", 9000, ProxyServer.BackendType.FRAMED, "storage"),
                new ProxyServer.BackendTarget("127.0.0.1", 9001, ProxyServer.BackendType.PLAIN, "api-1")
        ));

        Method chooseBackend = ProxyServer.class.getDeclaredMethod("chooseBackend", HttpRequest.class);
        chooseBackend.setAccessible(true);

        HttpRequest req = new HttpRequest("GET", "/kv/user1", "HTTP/1.1", new LinkedHashMap<>(), new byte[0]);
        Object backendClient = chooseBackend.invoke(server, req);

        Field targetField = backendClient.getClass().getDeclaredField("target");
        targetField.setAccessible(true);
        ProxyServer.BackendTarget target = (ProxyServer.BackendTarget) targetField.get(backendClient);
        assertEquals("storage", target.name());
    }

    @Test
    void returns503WhenBackendDropsConnectionDuringRequest() throws Exception {
        int backendPort;
        try (ServerSocket probe = new ServerSocket(0)) {
            backendPort = probe.getLocalPort();
        }
        CountDownLatch accepted = new CountDownLatch(1);
        Thread backend = new Thread(() -> runCrashBackend(backendPort, accepted));
        backend.setDaemon(true);
        backend.start();

        int proxyPort;
        try (ServerSocket probe = new ServerSocket(0)) {
            proxyPort = probe.getLocalPort();
        }
        ProxyServer proxy = new ProxyServer(proxyPort, List.of(
                new ProxyServer.BackendTarget("127.0.0.1", backendPort, ProxyServer.BackendType.PLAIN, "api-1")
        ));
        proxy.start();

        assertTrue(accepted.await(2, TimeUnit.SECONDS));

        try (Socket client = new Socket("127.0.0.1", proxyPort)) {
            client.setSoTimeout(3000);
            OutputStream out = client.getOutputStream();
            out.write(("GET /a HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.flush();

            BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
            String status = reader.readLine();
            assertNotNull(status);
            assertTrue(status.contains("503"));

            String line;
            int contentLength = 0;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                if (line.toLowerCase().startsWith("content-length:")) {
                    contentLength = Integer.parseInt(line.substring("content-length:".length()).trim());
                }
            }

            char[] body = new char[contentLength];
            int read = reader.read(body);
            String bodyText = read > 0 ? new String(body, 0, read) : "";
            assertTrue(bodyText.contains("\"error\":\"backend unavailable\""));
        }
    }

    private static void runCrashBackend(int port, CountDownLatch accepted) {
        try (ServerSocket backend = new ServerSocket(port)) {
            Socket client = backend.accept();
            accepted.countDown();
            client.close();
        } catch (Exception ignored) {
        }
    }
}
