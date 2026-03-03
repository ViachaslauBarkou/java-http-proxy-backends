package com.assignment.proxy;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class HttpModels {
    private HttpModels() {}

    public record HttpRequest(String method, String target, String version, Map<String, String> headers, byte[] body) {
        public byte[] toBytes() {
            StringBuilder sb = new StringBuilder();
            sb.append(method).append(" ").append(target).append(" ").append(version).append("\r\n");
            for (Map.Entry<String, String> e : headers.entrySet()) {
                sb.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
            }
            sb.append("\r\n");
            byte[] head = sb.toString().getBytes(StandardCharsets.US_ASCII);
            byte[] result = Arrays.copyOf(head, head.length + body.length);
            System.arraycopy(body, 0, result, head.length, body.length);
            return result;
        }
    }

    public record HttpResponse(String version, int statusCode, String reason, Map<String, String> headers, byte[] body) {
        public byte[] toBytes() {
            StringBuilder sb = new StringBuilder();
            sb.append(version).append(" ").append(statusCode).append(" ").append(reason).append("\r\n");
            for (Map.Entry<String, String> e : headers.entrySet()) {
                sb.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
            }
            sb.append("\r\n");
            byte[] head = sb.toString().getBytes(StandardCharsets.US_ASCII);
            byte[] result = Arrays.copyOf(head, head.length + body.length);
            System.arraycopy(body, 0, result, head.length, body.length);
            return result;
        }
    }

    public static HttpRequest readRequest(InputStream in) throws IOException {
        String start = readLine(in);
        if (start == null || start.isEmpty()) {
            return null;
        }
        String[] parts = start.split(" ", 3);
        if (parts.length != 3) {
            throw new IOException("Bad request line: " + start);
        }
        LinkedHashMap<String, String> headers = readHeaders(in);
        int contentLength = parseContentLength(headers);
        byte[] body = readN(in, contentLength);
        return new HttpRequest(parts[0], parts[1], parts[2], headers, body);
    }

    public static HttpResponse readResponse(InputStream in) throws IOException {
        String status = readLine(in);
        if (status == null || status.isEmpty()) {
            return null;
        }
        String[] parts = status.split(" ", 3);
        if (parts.length < 2) {
            throw new IOException("Bad status line: " + status);
        }
        String version = parts[0];
        int code = Integer.parseInt(parts[1]);
        String reason = parts.length == 3 ? parts[2] : "";
        LinkedHashMap<String, String> headers = readHeaders(in);
        int contentLength = parseContentLength(headers);
        byte[] body = readN(in, contentLength);
        return new HttpResponse(version, code, reason, headers, body);
    }

    public static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int prev = -1;
        while (true) {
            int b = in.read();
            if (b == -1) {
                if (baos.size() == 0 && prev == -1) {
                    return null;
                }
                throw new EOFException("Unexpected EOF while reading line");
            }
            if (prev == '\r' && b == '\n') {
                byte[] bytes = baos.toByteArray();
                return new String(bytes, 0, bytes.length - 1, StandardCharsets.US_ASCII);
            }
            baos.write(b);
            prev = b;
        }
    }

    private static LinkedHashMap<String, String> readHeaders(InputStream in) throws IOException {
        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        String line;
        while (!(line = readLine(in)).isEmpty()) {
            int idx = line.indexOf(':');
            if (idx <= 0) throw new IOException("Bad header line: " + line);
            headers.put(line.substring(0, idx).trim(), line.substring(idx + 1).trim());
        }
        return headers;
    }

    private static int parseContentLength(Map<String, String> headers) {
        String v = headers.entrySet().stream()
                .filter(e -> e.getKey().equalsIgnoreCase("Content-Length"))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse("0");
        return Integer.parseInt(v);
    }

    private static byte[] readN(InputStream in, int n) throws IOException {
        byte[] data = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(data, off, n - off);
            if (r == -1) throw new EOFException("Unexpected EOF body");
            off += r;
        }
        return data;
    }
}
