package com.assignment.proxy;

import com.assignment.proxy.HttpModels.HttpRequest;
import com.assignment.proxy.HttpModels.HttpResponse;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class ProxyServer {
    public enum BackendType { PLAIN, FRAMED }

    public record BackendTarget(String host, int port, BackendType type, String name) {}

    private final int port;
    private final List<BackendClient> backendClients;
    private final ExecutorService acceptPool = Executors.newCachedThreadPool();
    private final AtomicInteger rr = new AtomicInteger();

    public ProxyServer(int port, List<BackendTarget> targets) {
        this.port = port;
        this.backendClients = targets.stream().map(BackendClient::new).toList();
    }

    public void start() {
        backendClients.forEach(BackendClient::start);
        acceptPool.submit(() -> {
            try (ServerSocket server = new ServerSocket(port)) {
                while (true) {
                    Socket client = server.accept();
                    client.setTcpNoDelay(true);
                    acceptPool.submit(() -> handleClient(client));
                }
            } catch (IOException e) {
                throw new RuntimeException("Proxy failed", e);
            }
        });
    }

    private void handleClient(Socket socket) {
        BlockingQueue<CompletableFuture<HttpResponse>> responses = new ArrayBlockingQueue<>(256);
        try (socket; InputStream in = socket.getInputStream(); OutputStream out = socket.getOutputStream()) {
            Thread writer = new Thread(() -> {
                try {
                    while (true) {
                        CompletableFuture<HttpResponse> f = responses.take();
                        HttpResponse response = f.get();
                        out.write(response.toBytes());
                        out.flush();
                    }
                } catch (Exception ignored) {
                }
            });
            writer.start();

            while (true) {
                HttpRequest req = HttpModels.readRequest(in);
                if (req == null) break;
                BackendClient backend = chooseBackend();
                CompletableFuture<HttpResponse> future = backend.submit(normalize(req));
                responses.put(future);
            }
        } catch (Exception ignored) {
        }
    }

    private BackendClient chooseBackend() {
        int i = Math.floorMod(rr.getAndIncrement(), backendClients.size());
        return backendClients.get(i);
    }

    private HttpRequest normalize(HttpRequest req) {
        LinkedHashMap<String, String> h = new LinkedHashMap<>(req.headers());
        h.put("Connection", "keep-alive");
        h.putIfAbsent("Content-Length", String.valueOf(req.body().length));
        return new HttpRequest(req.method(), req.target(), req.version(), h, req.body());
    }

    private static class BackendJob {
        final HttpRequest req;
        final CompletableFuture<HttpResponse> future;
        final boolean health;

        BackendJob(HttpRequest req, CompletableFuture<HttpResponse> future, boolean health) {
            this.req = req;
            this.future = future;
            this.health = health;
        }
    }

    private static class BackendClient {
        private final BackendTarget target;
        private final BlockingQueue<BackendJob> queue = new ArrayBlockingQueue<>(512);
        private final ScheduledExecutorService healthExec = Executors.newSingleThreadScheduledExecutor();
        private volatile Socket socket;
        private volatile InputStream in;
        private volatile OutputStream out;

        BackendClient(BackendTarget target) {
            this.target = target;
        }

        void start() {
            Thread t = new Thread(this::runLoop, "backend-client-" + target.name());
            t.start();
            healthExec.scheduleAtFixedRate(this::scheduleHealthCheck, 2, 2, TimeUnit.SECONDS);
        }

        CompletableFuture<HttpResponse> submit(HttpRequest req) throws InterruptedException {
            CompletableFuture<HttpResponse> f = new CompletableFuture<>();
            queue.put(new BackendJob(req, f, false));
            return f;
        }

        private void scheduleHealthCheck() {
            HttpRequest req = new HttpRequest("GET", "/__health", "HTTP/1.1", new LinkedHashMap<>(Map.of("Host", target.host(), "Content-Length", "0")), new byte[0]);
            CompletableFuture<HttpResponse> f = new CompletableFuture<>();
            queue.offer(new BackendJob(req, f, true));
            f.orTimeout(2, TimeUnit.SECONDS).exceptionally(ex -> {
                close();
                return null;
            });
        }

        private void runLoop() {
            while (true) {
                try {
                    ensureConnected();
                    BackendJob job = queue.take();
                    HttpResponse response = execute(job.req);
                    if (!job.health) {
                        job.future.complete(response);
                    } else {
                        job.future.complete(response);
                    }
                } catch (Exception e) {
                    close();
                    failPending(e);
                    sleepQuietly(200);
                }
            }
        }

        private HttpResponse execute(HttpRequest req) throws IOException {
            if (target.type() == BackendType.PLAIN) {
                out.write(req.toBytes());
                out.flush();
                return HttpModels.readResponse(in);
            }
            FramedCodec.writeFrame(out, req.toBytes());
            byte[] frame = FramedCodec.readFrame(in);
            if (frame == null) throw new EOFException("No frame from storage backend");
            return HttpModels.readResponse(new ByteArrayInputStream(frame));
        }

        private void ensureConnected() throws IOException {
            if (socket != null && socket.isConnected() && !socket.isClosed()) return;
            Socket s = new Socket(target.host(), target.port());
            s.setTcpNoDelay(true);
            socket = s;
            in = s.getInputStream();
            out = s.getOutputStream();
        }

        private void close() {
            try { if (socket != null) socket.close(); } catch (IOException ignored) {}
            socket = null;
            in = null;
            out = null;
        }

        private void failPending(Exception e) {
            List<BackendJob> drained = new ArrayList<>();
            queue.drainTo(drained);
            for (BackendJob job : drained) {
                if (!job.health) {
                    job.future.complete(errorResponse(e, target.name()));
                }
            }
        }

        private static HttpResponse errorResponse(Exception e, String backendName) {
            String body = "{\"error\":\"backend unavailable\",\"backend\":\"" + backendName + "\",\"detail\":\"" + e.getClass().getSimpleName() + "\"}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            LinkedHashMap<String, String> headers = new LinkedHashMap<>();
            headers.put("Content-Type", "application/json");
            headers.put("Content-Length", String.valueOf(bytes.length));
            headers.put("Connection", "keep-alive");
            return new HttpResponse("HTTP/1.1", 503, "Service Unavailable", headers, bytes);
        }

        private static void sleepQuietly(long ms) {
            try { Thread.sleep(ms); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
    }
}
