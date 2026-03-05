package com.assignment.proxy;

import com.assignment.proxy.HttpModels.HttpRequest;
import com.assignment.proxy.HttpModels.HttpResponse;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Reverse proxy that distributes incoming requests across plain and framed backends.
 */
public class ProxyServer {
    /**
     * Backend transport protocol.
     */
    public enum BackendType { PLAIN, FRAMED }

    /**
     * Backend destination settings.
     *
     * @param host host name or IP.
     * @param port TCP port.
     * @param type backend protocol type.
     * @param name backend name for diagnostics.
     */
    public record BackendTarget(String host, int port, BackendType type, String name) {}

    private final int port;
    private final List<BackendClient> backendClients;
    private final ExecutorService acceptPool = Executors.newCachedThreadPool();
    private final AtomicInteger rr = new AtomicInteger();

    /**
     * Creates a proxy server.
     *
     * @param port listen port.
     * @param targets backend targets used by round robin balancing.
     */
    public ProxyServer(int port, List<BackendTarget> targets) {
        this.port = port;
        this.backendClients = targets.stream().map(BackendClient::new).toList();
    }

    /**
     * Starts backend clients and begins accepting client connections.
     */
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

    /**
     * Pipes requests from one client connection through backend workers.
     */
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
                BackendClient backend = chooseBackend(req);
                CompletableFuture<HttpResponse> future = backend.submit(normalize(req));
                responses.put(future);
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * Chooses next backend using round robin.
     */
    private BackendClient chooseBackend() {
        int i = Math.floorMod(rr.getAndIncrement(), backendClients.size());
        return backendClients.get(i);
    }

    /**
     * Chooses backend by route, with round-robin fallback.
     */
    private BackendClient chooseBackend(HttpRequest req) {
        if (req.target().startsWith("/kv/")) {
            for (BackendClient backendClient : backendClients) {
                if (backendClient.target.name().equals("storage")) {
                    return backendClient;
                }
            }
        }
        return chooseBackend();
    }

    /**
     * Ensures headers required for persistent backend connections are present.
     */
    private HttpRequest normalize(HttpRequest req) {
        LinkedHashMap<String, String> h = new LinkedHashMap<>(req.headers());
        h.put("Connection", "keep-alive");
        h.putIfAbsent("Content-Length", String.valueOf(req.body().length));
        return new HttpRequest(req.method(), req.target(), req.version(), h, req.body());
    }

    /**
     * One queued backend task.
     */
    private static class BackendJob {
        final HttpRequest req;
        final CompletableFuture<HttpResponse> future;
        final boolean health;

        /**
         * Creates backend queue job.
         */
        BackendJob(HttpRequest req, CompletableFuture<HttpResponse> future, boolean health) {
            this.req = req;
            this.future = future;
            this.health = health;
        }
    }

    /**
     * Persistent connection worker for one backend target.
     */
    private static class BackendClient {
        private final BackendTarget target;
        private final BlockingQueue<BackendJob> queue = new ArrayBlockingQueue<>(512);
        private final ScheduledExecutorService healthExec = Executors.newSingleThreadScheduledExecutor();
        private volatile Socket socket;
        private volatile InputStream in;
        private volatile OutputStream out;

        /**
         * Creates backend client.
         */
        BackendClient(BackendTarget target) {
            this.target = target;
        }

        /**
         * Starts worker loop and periodic health checks.
         */
        void start() {
            Thread t = new Thread(this::runLoop, "backend-client-" + target.name());
            t.start();
            healthExec.scheduleAtFixedRate(this::scheduleHealthCheck, 2, 2, TimeUnit.SECONDS);
        }

        /**
         * Queues a business request to the backend.
         */
        CompletableFuture<HttpResponse> submit(HttpRequest req) throws InterruptedException {
            CompletableFuture<HttpResponse> f = new CompletableFuture<>();
            queue.put(new BackendJob(req, f, false));
            return f;
        }

        /**
         * Enqueues health-check request and closes connection on timeout.
         */
        private void scheduleHealthCheck() {
            HttpRequest req = new HttpRequest("GET", "/__health", "HTTP/1.1", new LinkedHashMap<>(Map.of("Host", target.host(), "Content-Length", "0")), new byte[0]);
            CompletableFuture<HttpResponse> f = new CompletableFuture<>();
            queue.offer(new BackendJob(req, f, true));
            f.orTimeout(2, TimeUnit.SECONDS).exceptionally(ex -> {
                close();
                return null;
            });
        }

        /**
         * Main loop that executes queued jobs over persistent socket.
         */
        private void runLoop() {
            while (true) {
                BackendJob job = null;
                try {
                    ensureConnected();
                    job = queue.take();
                    HttpResponse response = execute(job.req);
                    if (!job.health) {
                        job.future.complete(response);
                    } else {
                        job.future.complete(response);
                    }
                } catch (Exception e) {
                    close();
                    failCurrent(job, e);
                    failPending(e);
                    sleepQuietly(200);
                }
            }
        }

        /**
         * Sends one request and reads one response according to backend protocol.
         */
        private HttpResponse execute(HttpRequest req) throws IOException {
            if (target.type() == BackendType.PLAIN) {
                out.write(req.toBytes());
                out.flush();
                HttpResponse response = HttpModels.readResponse(in);
                if (response == null) {
                    throw new EOFException("No response from plain backend");
                }
                return response;
            }
            FramedCodec.writeFrame(out, req.toBytes());
            byte[] frame = FramedCodec.readFrame(in);
            if (frame == null) throw new EOFException("No frame from storage backend");
            return HttpModels.readResponse(new ByteArrayInputStream(frame));
        }

        /**
         * Opens backend socket if it is not connected yet.
         */
        private void ensureConnected() throws IOException {
            if (socket != null && socket.isConnected() && !socket.isClosed()) return;
            Socket s = new Socket(target.host(), target.port());
            s.setTcpNoDelay(true);
            socket = s;
            in = s.getInputStream();
            out = s.getOutputStream();
        }

        /**
         * Closes and resets backend socket state.
         */
        private void close() {
            try { if (socket != null) socket.close(); } catch (IOException ignored) {}
            socket = null;
            in = null;
            out = null;
        }

        /**
         * Completes queued user requests with synthetic error response.
         */
        private void failPending(Exception e) {
            List<BackendJob> drained = new ArrayList<>();
            queue.drainTo(drained);
            for (BackendJob job : drained) {
                if (!job.health) {
                    job.future.complete(errorResponse(e, target.name()));
                }
            }
        }

        /**
         * Completes currently running user request if backend fails mid-flight.
         */
        private void failCurrent(BackendJob job, Exception e) {
            if (job != null && !job.health) {
                job.future.complete(errorResponse(e, target.name()));
            }
        }

        /**
         * Creates 503 JSON response used when backend connection fails.
         */
        private static HttpResponse errorResponse(Exception e, String backendName) {
            String body = "{\"error\":\"backend unavailable\",\"backend\":\"" + backendName + "\",\"detail\":\"" + e.getClass().getSimpleName() + "\"}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            LinkedHashMap<String, String> headers = new LinkedHashMap<>();
            headers.put("Content-Type", "application/json");
            headers.put("Content-Length", String.valueOf(bytes.length));
            headers.put("Connection", "keep-alive");
            return new HttpResponse("HTTP/1.1", 503, "Service Unavailable", headers, bytes);
        }

        /**
         * Sleeps without throwing checked interruption errors.
         */
        private static void sleepQuietly(long ms) {
            try { Thread.sleep(ms); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
    }
}
