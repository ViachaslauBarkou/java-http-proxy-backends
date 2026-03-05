package com.assignment.proxy;

import com.assignment.proxy.HttpModels.HttpRequest;
import com.assignment.proxy.HttpModels.HttpResponse;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.*;

/**
 * Storage-like backend that communicates over a custom framed protocol.
 */
public class FramedStorageBackendServer {
    private final int port;
    private final Map<String, byte[]> storage = new ConcurrentHashMap<>();
    private final ExecutorService pool = Executors.newCachedThreadPool();

    /**
     * Creates framed backend.
     *
     * @param port listen port.
     */
    public FramedStorageBackendServer(int port) {
        this.port = port;
    }

    /**
     * Starts accepting framed backend connections asynchronously.
     */
    public void start() {
        pool.submit(() -> {
            try (ServerSocket server = new ServerSocket(port)) {
                while (true) {
                    Socket socket = server.accept();
                    socket.setTcpNoDelay(true);
                    pool.submit(() -> handleConnection(socket));
                }
            } catch (IOException e) {
                throw new RuntimeException("Framed storage backend failed", e);
            }
        });
    }

    /**
     * Handles one framed transport connection.
     */
    private void handleConnection(Socket socket) {
        BlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(256);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (socket; InputStream in = socket.getInputStream(); OutputStream out = socket.getOutputStream()) {
            Thread reader = new Thread(() -> {
                try {
                    while (true) {
                        byte[] frame = FramedCodec.readFrame(in);
                        if (frame == null) break;
                        queue.put(frame);
                    }
                } catch (Exception ignored) {
                }
            });
            reader.start();

            worker.submit(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        byte[] reqFrame = queue.take();
                        HttpRequest req = HttpModels.readRequest(new ByteArrayInputStream(reqFrame));
                        HttpResponse response = process(req);
                        FramedCodec.writeFrame(out, response.toBytes());
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

    /**
     * Processes one request and returns a JSON response.
     */
    private HttpResponse process(HttpRequest req) {
        if (req.target().equals("/__health")) {
            return response(200, "OK", "storage-healthy");
        }

        if (!req.target().startsWith("/kv/")) {
            return response(404, "Not Found", "not found", "text/plain");
        }

        String key = req.target().substring("/kv/".length());
        if (key.isEmpty()) {
            return response(400, "Bad Request", "key is required", "text/plain");
        }

        return switch (req.method()) {
            case "PUT" -> {
                storage.put(key, req.body());
                yield response(200, "OK", "", "text/plain");
            }
            case "GET" -> {
                byte[] value = storage.get(key);
                if (value == null) {
                    yield response(404, "Not Found", "not found", "text/plain");
                }
                yield binaryResponse(200, "OK", value, "text/plain");
            }
            case "DELETE" -> {
                storage.remove(key);
                yield response(200, "OK", "", "text/plain");
            }
            default -> response(405, "Method Not Allowed", "method not allowed", "text/plain");
        };
    }

    /**
     * Creates a JSON response with keep-alive headers.
     */
    private HttpResponse response(int code, String reason, String body) {
        return response(code, reason, body, "application/json");
    }

    /**
     * Creates a text or JSON response with keep-alive headers.
     */
    private HttpResponse response(int code, String reason, String body, String contentType) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return binaryResponse(code, reason, bytes, contentType);
    }

    /**
     * Creates a binary response with keep-alive headers.
     */
    private HttpResponse binaryResponse(int code, String reason, byte[] body, String contentType) {
        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", contentType);
        headers.put("Content-Length", String.valueOf(body.length));
        headers.put("Connection", "keep-alive");
        return new HttpResponse("HTTP/1.1", code, reason, headers, body);
    }
}
