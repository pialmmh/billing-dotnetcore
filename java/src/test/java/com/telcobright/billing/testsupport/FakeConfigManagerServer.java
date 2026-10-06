package com.telcobright.billing.testsupport;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A minimal HTTP/1.1 config-manager stand-in on a raw socket, for connection-lifecycle tests. Every request gets the
 * configured status + body; keep-alive is honoured (the next request may arrive on the same connection), and the
 * server counts the connections the client still holds — a connection counts as open until the CLIENT closes it.
 *
 * <p>The body is sent chunked, each piece flushed separately with a short pause, the way a real server streams an
 * error page. That is the shape that strands a connection whose body is never read: the client parks the response
 * before its last chunk, so the connection is neither returned to the pool nor closed.</p>
 */
public final class FakeConfigManagerServer implements AutoCloseable {
    private final ServerSocket _server;
    private final ExecutorService _pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "fake-config-manager");
        t.setDaemon(true);
        return t;
    });
    private final Set<Socket> _sockets = ConcurrentHashMap.newKeySet();
    private final AtomicInteger _accepted = new AtomicInteger();
    private final AtomicInteger _open = new AtomicInteger();
    private final Map<String, AtomicInteger> _requestsByPath = new ConcurrentHashMap<>();
    private volatile int _status = 500;
    private volatile List<String> _bodyPieces = List.of("{\"status\":500,\"error\":\"Internal Server Error\"}");

    public FakeConfigManagerServer() throws IOException {
        _server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        _pool.submit(this::AcceptLoop);
    }

    /** Answer every following request with {@code status} and a chunked body made of {@code bodyPieces}. */
    public FakeConfigManagerServer Respond(int status, List<String> bodyPieces) {
        _status = status;
        _bodyPieces = List.copyOf(bodyPieces);
        return this;
    }

    public String BaseUrl() {
        return "http://127.0.0.1:" + _server.getLocalPort();
    }

    public int Accepted() {
        return _accepted.get();
    }

    public int Open() {
        return _open.get();
    }

    public int Requests(String path) {
        AtomicInteger n = _requestsByPath.get(path);
        return n == null ? 0 : n.get();
    }

    /** Give the client up to {@code max} to release connections down to {@code atMost}; returns how many remain. */
    public int OpenAfterSettling(int atMost, Duration max) throws InterruptedException {
        long deadline = System.nanoTime() + max.toNanos();
        while (_open.get() > atMost && System.nanoTime() < deadline) Thread.sleep(20);
        return _open.get();
    }

    @Override
    public void close() throws IOException {
        _server.close();
        for (Socket s : _sockets) {
            try {
                s.close();
            } catch (IOException ignored) {
                // best-effort teardown
            }
        }
        _pool.shutdownNow();
    }

    private void AcceptLoop() {
        while (!_server.isClosed()) {
            try {
                Socket s = _server.accept();
                _accepted.incrementAndGet();
                _open.incrementAndGet();
                _sockets.add(s);
                _pool.submit(() -> Serve(s));
            } catch (IOException e) {
                return;   // server closed
            }
        }
    }

    private void Serve(Socket s) {
        try (s) {
            InputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();
            while (true) {
                RequestHead head = ReadRequestHead(in);
                if (head == null) return;                      // the client closed the connection
                in.skipNBytes(head.contentLength);
                _requestsByPath.computeIfAbsent(head.path, k -> new AtomicInteger()).incrementAndGet();
                WriteResponse(out);
            }
        } catch (IOException | InterruptedException e) {
            // reset/closed by the client (or teardown) — the connection is gone either way
        } finally {
            _sockets.remove(s);
            _open.decrementAndGet();
        }
    }

    private void WriteResponse(OutputStream out) throws IOException, InterruptedException {
        int status = _status;
        String reason = status == 200 ? "OK" : "Internal Server Error";
        out.write(("HTTP/1.1 " + status + " " + reason + "\r\n"
                + "Content-Type: application/json\r\n"
                + "Transfer-Encoding: chunked\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();
        for (String piece : _bodyPieces) {
            byte[] b = piece.getBytes(StandardCharsets.UTF_8);
            out.write((Integer.toHexString(b.length) + "\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(b);
            out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            Thread.sleep(5);
        }
        out.write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    private record RequestHead(String path, long contentLength) {}

    /** Read one request line + headers; null when the client closed the connection before sending one. */
    private static RequestHead ReadRequestHead(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int matched = 0;
        while (matched < 4) {
            int b = in.read();
            if (b < 0) return null;
            buf.write(b);
            matched = (b == (matched % 2 == 0 ? '\r' : '\n')) ? matched + 1 : (b == '\r' ? 1 : 0);
        }
        String[] lines = buf.toString(StandardCharsets.US_ASCII).split("\r\n");
        String target = lines[0].split(" ")[1];
        int q = target.indexOf('?');
        Map<String, String> headers = new HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int c = lines[i].indexOf(':');
            if (c > 0) headers.put(lines[i].substring(0, c).trim().toLowerCase(Locale.ROOT), lines[i].substring(c + 1).trim());
        }
        long len = Long.parseLong(headers.getOrDefault("content-length", "0"));
        return new RequestHead(q < 0 ? target : target.substring(0, q), len);
    }
}
