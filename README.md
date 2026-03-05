# Java HTTP Proxy + Backend Servers

This assignment is implemented as a small Java 21 project using plain `ServerSocket`/`Socket` APIs without frameworks.

## Requirements

- Java 21+
- Maven 3.9+
- `nc` (netcat) for manual smoke checks (optional)

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
- Routes `/kv/*` requests to the framed storage backend.
- Balances all other requests in round-robin mode across 4 regular HTTP backends.

### 2) Backend Servers
Two backend server types are implemented.

#### Framed Storage Backend (`9000`)
- Uses length-prefixed framing (4-byte length + payload).
- Raw HTTP request is sent in the payload; raw HTTP response is returned back.
- Implements real in-memory key-value storage at `/kv/{key}`:
  - `PUT /kv/{key}` writes request body as a value,
  - `GET /kv/{key}` returns stored value,
  - `DELETE /kv/{key}` removes key,
  - `GET` for a missing key returns `404 Not Found`.
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

```text
Client -> Proxy:8080 -> [storage:9000, api-1..4:9001..9004]
```

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

For macOS, run this variant:

```bash
{ printf 'GET /a HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\n\r\nGET /b HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\n\r\n'; sleep 1; } | nc 127.0.0.1 8080
```

You should see 2 responses in the same order (`/a`, then `/b`) — this verifies pipelining ordering.

KV storage check:

```bash
printf 'PUT /kv/user1 HTTP/1.1\r\nHost: localhost\r\nContent-Length: 5\r\n\r\nhello' | nc 127.0.0.1 8080
printf 'GET /kv/user1 HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\n\r\n' | nc 127.0.0.1 8080
printf 'DELETE /kv/user1 HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\n\r\n' | nc 127.0.0.1 8080
```

---

## How to run tests

Run all unit tests:

```bash
mvn -q test
```

Run one specific test class:

```bash
mvn -q -Dtest=ProxyServerTest test
```

---

## Project structure

```text
src/main/java/com/assignment/proxy
src/test/java/com/assignment/proxy
pom.xml
```

---

## License

This project is distributed under the MIT License. See [LICENSE](LICENSE) for details.

---

## Contributing

This repository was prepared as an assignment, but small improvements and fixes are welcome via pull requests.

---

## How AI was used

Most of this project was implemented with AI assistance, and I reviewed and corrected the generated code where needed.
