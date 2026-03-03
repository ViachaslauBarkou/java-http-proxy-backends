# Java HTTP Proxy + Backend Servers

Тестовое задание реализовано как небольшой Java 17 проект на чистых `ServerSocket`/`Socket` без фреймворков.

## Что реализовано

### 1) Proxy Server
- Принимает клиентские соединения по HTTP/1.1 (порт `8080`).
- Поддерживает HTTP pipelining:
  - отдельный поток читает запросы клиента и кладёт их в очередь,
  - отдельный поток пишет ответы,
  - ответы отправляются строго в том же порядке, в котором были прочитаны запросы.
- Не отбрасывает запросы при нагрузке:
  - используются ограниченные `ArrayBlockingQueue`,
  - при заполнении очереди поток чтения блокируется (backpressure), а не отдаёт ошибку/дропает запрос.
- Балансирует запросы round-robin между 5 backend-ами:
  - 1 framed storage backend,
  - 4 обычных HTTP backend-а.

### 2) Backend Servers
Реализовано 2 типа backend серверов.

#### Framed Storage Backend (`9000`)
- Использует length-prefixed framing (4 байта длины + payload).
- В payload передаётся raw HTTP request, обратно возвращается raw HTTP response.
- На каждое TCP-соединение:
  - входящие кадры сначала ставятся в очередь,
  - обработка идёт отдельным worker-потоком,
  - I/O thread не выполняет бизнес-логику.

#### Plain HTTP Backend (`9001..9004`)
- Получает обычные HTTP/1.1 запросы.
- На каждое TCP-соединение:
  - reader поток только парсит запросы и складывает в очередь,
  - worker поток обрабатывает и формирует ответ,
  - I/O чтение не блокируется обработкой бизнес-логики.

### 3) Keepalive / Health
- Прокси держит persistent connection к каждому backend.
- Каждые 2 секунды в очередь backend-клиента добавляется health check:
  - `GET /__health` для plain backend,
  - такой же HTTP запрос, но внутри frame для storage backend.
- При ошибках чтения/записи соединение закрывается и автоматически переподключается.

---

## Архитектура (коротко)

- `Main` поднимает все backend серверы и затем proxy.
- `ProxyServer`:
  - на клиентском соединении — read loop + ordered write loop,
  - внутри — пул `BackendClient` (по одному на backend endpoint),
  - каждый `BackendClient` последовательно отправляет запросы в свой backend и получает ответы, сохраняя соответствие request->response.
- `HttpModels` — минимальный HTTP parser/serializer (request/response, headers, content-length).
- `FramedCodec` — framing для storage backend.

---

## Ограничения и trade-offs (осознанно)

Из-за ограничения по времени (~6–8 часов):
- Поддерживается `Content-Length`, но не реализован chunked transfer encoding.
- Минимальная обработка ошибок HTTP парсинга (основные кейсы покрыты).
- Нет TLS/HTTPS и нет полноценного graceful shutdown.
- Простая стратегия load balancing (round-robin), без latency-aware эвристик.
- In-memory без персистентного хранилища.

---

## Что улучшил бы дальше

1. Добавил бы NIO/Netty вместо per-connection thread модели для лучшей масштабируемости.
2. Поддержал бы chunked encoding и стриминг тел.
3. Добавил бы полноценные retries с idempotency-aware политиками.
4. Ввёл бы метрики (Prometheus), структурированный логгинг, tracing.
5. Добавил бы интеграционные и нагрузочные тесты (Gatling/JMH + chaos scenarios).
6. Сделал бы конфигурацию через env/YAML и health/readiness endpoints.

---

## Как запускать

```bash
mvn -q compile
mvn -q exec:java -Dexec.mainClass=com.assignment.proxy.Main
```

> Если в вашем Maven не подключен `exec-maven-plugin`, можно собрать jar и запустить `java -cp ...`.

Пример проверки:

```bash
printf 'GET /a HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\n\r\nGET /b HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\n\r\n' | nc 127.0.0.1 8080
```

Вы увидите 2 ответа в том же порядке (`/a`, затем `/b`) — это проверка pipelining ordering.

---

## How AI was used

Я использовал AI как ускоритель проектирования и чернового кода:
1. Сформулировал архитектуру: как разнести I/O, очередь и worker на connection-level.
2. Сгенерировал стартовые шаблоны классов (proxy/backends/codecs).
3. Проверил логические требования задания (ordering, backpressure, keepalive).

Где AI ошибался/требовал правок вручную:
- Предлагал API, завязанные на более новую Java (virtual/platform thread builders), пришлось заменить на совместимый код Java 17.
- Нужна была ручная корректировка сценария keepalive, чтобы не ломать общий поток request/response.
- Ручная валидация потоков обработки и очередей, чтобы соответствовать требованиям “no rejection under load” и “preserve ordering”.

Итог: AI дал скорость на boilerplate, но критичные решения по протоколу, очередям и отказоустойчивости были доработаны вручную.
