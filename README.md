# Java HTTP Proxy + Backend Servers

This assignment is implemented as a small Java 21 project using plain `ServerSocket`/`Socket` APIs without frameworks.

## What is implemented

### 1) Proxy Server
- Accepts client HTTP/1.1 connections (port `8080`).
- Supports HTTP pipelining:
  - a dedicated thread reads client requests and enqueues them,
  - a separate thread writes responses,
  - responses are sent strictly in the same order in which requests were read.
- Does not drop requests under load:
  - bounded `ArrayBlockingQueue` instances are used,
  - when a queue is full, the reader thread blocks (backpressure) instead of returning an error or dropping a request.
- Balances requests in round-robin mode across 5 backends:
  - 1 framed storage backend,
  - 4 regular HTTP backends.

### 2) Backend Servers
Two backend server types are implemented.

#### Framed Storage Backend (`9000`)
- Uses length-prefixed framing (4-byte length + payload).
- Raw HTTP request is sent in the payload; raw HTTP response is returned back.
- For each TCP connection:
  - incoming frames are first enqueued,
  - processing is performed by a separate worker thread,
  - the I/O thread does not execute business logic.

#### Plain HTTP Backend (`9001..9004`)
- Receives regular HTTP/1.1 requests.
- For each TCP connection:
  - the reader thread only parses requests and enqueues them,
  - the worker thread processes requests and builds responses,
  - I/O reading is not blocked by business logic processing.

### 3) Keepalive / Health
- The proxy keeps a persistent connection to each backend.
- Every 2 seconds, a health check is added to the backend client queue:
  - `GET /__health` for plain backends,
  - the same HTTP request wrapped in a frame for the storage backend.
- On read/write errors, the connection is closed and automatically re-established.

---

## Architecture (short)

- `Main` starts all backend servers and then the proxy.
- `ProxyServer`:
  - on each client connection: read loop + ordered write loop,
  - internally: a pool of `BackendClient` instances (one per backend endpoint),
  - each `BackendClient` sends requests to its backend sequentially and receives matching responses while preserving request->response mapping.
- `HttpModels` is a minimal HTTP parser/serializer (request/response, headers, content-length).
- `FramedCodec` provides framing for the storage backend.

---

## Limitations and trade-offs (intentional)

Due to time constraints (~6–8 hours):
- `Content-Length` is supported, but chunked transfer encoding is not implemented.
- HTTP parsing error handling is minimal (main scenarios are covered).
- No TLS/HTTPS and no full graceful shutdown implementation.
- Simple load balancing strategy (round-robin), without latency-aware heuristics.
- In-memory only, without persistent storage.

---

## What I would improve next

1. Use NIO/Netty instead of the per-connection thread model for better scalability.
2. Add chunked encoding support and body streaming.
3. Add proper retries with idempotency-aware policies.
4. Introduce metrics (Prometheus), structured logging, and tracing.
5. Add integration and load tests (Gatling/JMH + chaos scenarios).
6. Move configuration to env/YAML and add health/readiness endpoints.

---

## How to run

```bash
mvn -q compile
mvn -q exec:java -Dexec.mainClass=com.assignment.proxy.Main
```

> If your Maven setup does not include `exec-maven-plugin`, you can build a jar and run it via `java -cp ...`.

Example check:

```bash
printf 'GET /a HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\n\r\nGET /b HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\n\r\n' | nc 127.0.0.1 8080
```

You should see 2 responses in the same order (`/a`, then `/b`) — this verifies pipelining ordering.

---

## How AI was used

I used AI as an accelerator for design and boilerplate code:
1. Defined the architecture: how to separate I/O, queueing, and worker processing at connection level.
2. Generated starter class templates (proxy/backends/codecs).
3. Cross-checked assignment requirements (ordering, backpressure, keepalive).

Where AI was wrong / required manual fixes:
- It suggested APIs tied to newer Java versions (virtual/platform thread builders), so I replaced them with compatibility-safe code for this project setup.
- Keepalive flow needed manual adjustment so it would not break the shared request/response stream.
- Processing threads and queues required manual validation to satisfy “no rejection under load” and “preserve ordering”.

Summary: AI sped up boilerplate, but critical protocol, queueing, and resilience decisions were finalized manually.
