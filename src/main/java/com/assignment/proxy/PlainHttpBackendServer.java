package com.assignment.proxy;

import com.assignment.proxy.HttpModels.HttpRequest;
import com.assignment.proxy.HttpModels.HttpResponse;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.concurrent.*;

public class PlainHttpBackendServer {
    private final int port;
    private final String name;
    private final ExecutorService connPool = Executors.newCachedThreadPool();

    public PlainHttpBackendServer(int port, String name) {
        this.port = port;
        this.name = name;
    }

    public void start() {
        connPool.submit(() -> {
            try (ServerSocket server = new ServerSocket(port)) {
                while (true) {
                    Socket socket = server.accept();
                    socket.setTcpNoDelay(true);
                    connPool.submit(() -> handleConnection(socket));
                }
            } catch (IOException e) {
                throw new RuntimeException("Backend " + name + " failed", e);
            }
        });
    }

    private void handleConnection(Socket socket) {
        BlockingQueue<HttpRequest> queue = new ArrayBlockingQueue<>(256);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (socket; InputStream in = socket.getInputStream(); OutputStream out = socket.getOutputStream()) {
            Thread reader = new Thread(() -> {
                try {
                    while (true) {
                        HttpRequest req = HttpModels.readRequest(in);
                        if (req == null) break;
                        queue.put(req); // backpressure if worker is busy
                    }
                } catch (Exception ignored) {
                }
            });
            reader.start();

            worker.submit(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        HttpRequest req = queue.take();
                        HttpResponse response = process(req);
                        out.write(response.toBytes());
                        out.flush();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    } catch (IOException e) {
                        return;
                    }
                }
            });
            reader.join();
        } catch (Exception ignored) {
        } finally {
            worker.shutdownNow();
        }
    }

    private HttpResponse process(HttpRequest req) {
        if (req.target().equals("/__health")) {
            return response(200, "OK", "healthy:" + name);
        }
        String body = "{\"backend\":\"" + name + "\",\"method\":\"" + req.method() + "\",\"path\":\"" + req.target() + "\"}";
        return response(200, "OK", body);
    }

    private HttpResponse response(int code, String reason, String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Content-Length", String.valueOf(bytes.length));
        headers.put("Connection", "keep-alive");
        return new HttpResponse("HTTP/1.1", code, reason, headers, bytes);
    }
}
