package client;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.awt.Desktop;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Jembatan Web untuk klien NetQuiz. Browser tidak boleh membuka soket TCP/UDP mentah, jadi
 * proses kecil ini (berjalan di komputer pemain) melayani halaman web/index.html dan
 * meneruskan aksi browser ke server NetQuiz lewat NetQuizConnection (TANPA diubah).
 *
 *   Browser --HTTP/SSE--> NetQuizWebBridge --TCP 12345 + UDP 12346--> NetQuizServer
 *
 * Seluruh soket tetap dikelola background thread milik NetQuizConnection, sehingga syarat
 * panduan 2.6 (soket tidak di event handler UI) tetap terpenuhi. Server tidak berubah.
 *
 * Endpoint: POST /api/connect, /api/request, /api/buzz, /api/disconnect;
 *           GET /api/events (Server-Sent Events, push+tick+log), GET /api/cert (PDF).
 */
public class NetQuizWebBridge {

    private static final Gson GSON = new Gson();
    private static final Map<String, Session> SESSIONS = new ConcurrentHashMap<String, Session>();
    private static Path webDir;

    /** Satu tab browser = satu koneksi NetQuiz. Event dari server diantrikan untuk SSE. */
    static class Session implements NetQuizConnection.Listener {
        final String id = UUID.randomUUID().toString();
        final BlockingQueue<String> events = new LinkedBlockingQueue<String>();
        final NetQuizConnection conn = new NetQuizConnection(this);

        private void emit(JsonObject o) { events.offer(o.toString()); }

        @Override public void onLog(String message) {
            ClientLogger.log("[WEB " + id.substring(0, 6) + "] " + message);
            JsonObject o = new JsonObject();
            o.addProperty("t", "log");
            o.addProperty("m", message);
            emit(o);
        }
        @Override public void onPush(String type, String[] parts) {
            JsonObject o = new JsonObject();
            o.addProperty("t", "push");
            o.addProperty("type", type);
            o.add("parts", GSON.toJsonTree(parts));
            emit(o);
        }
        @Override public void onTick(int secondsLeft) {
            JsonObject o = new JsonObject();
            o.addProperty("t", "tick");
            o.addProperty("s", secondsLeft);
            emit(o);
        }
        @Override public void onDisconnected(String reason) {
            JsonObject o = new JsonObject();
            o.addProperty("t", "disc");
            o.addProperty("reason", reason);
            emit(o);
        }
    }

    // ------------------------------------------------------------------ main

    public static void main(String[] args) throws Exception {
        int port = args.length >= 1 ? Integer.parseInt(args[0]) : 8080;
        String bind = args.length >= 2 ? args[1] : "127.0.0.1";   // lokal saja secara default
        webDir = Paths.get("web");
        if (!Files.exists(webDir.resolve("index.html"))) {
            System.err.println("web/index.html tidak ditemukan. Jalankan dari folder proyek NetQuizV3.");
            System.exit(1);
        }
        HttpServer http = HttpServer.create(new InetSocketAddress(bind, port), 0);
        http.createContext("/", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException { route(ex); }
        });
        http.setExecutor(Executors.newCachedThreadPool());       // SSE menahan satu thread per tab
        http.start();
        String url = "http://" + ("0.0.0.0".equals(bind) ? "localhost" : bind) + ":" + port + "/";
        ClientLogger.log("[WEB] NetQuiz Web GUI aktif di " + url);
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE))
                Desktop.getDesktop().browse(new URI(url));
        } catch (Exception ignored) {}
    }

    // ---------------------------------------------------------------- routing

    private static void route(HttpExchange ex) throws IOException {
        try {
            if (!sameOrigin(ex)) { json(ex, 403, err("Origin ditolak.")); return; }
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            if (method.equals("GET") && (path.equals("/") || path.equals("/index.html"))) {
                bytes(ex, 200, "text/html; charset=utf-8", Files.readAllBytes(webDir.resolve("index.html")));
            } else if (method.equals("GET") && path.equals("/style.css")) {
                bytes(ex, 200, "text/css; charset=utf-8", Files.readAllBytes(webDir.resolve("style.css")));
            } else if (method.equals("GET") && path.equals("/app.js")) {
                bytes(ex, 200, "application/javascript; charset=utf-8", Files.readAllBytes(webDir.resolve("app.js")));
            } else if (path.equals("/api/connect") && method.equals("POST")) {
                connect(ex);
            } else if (path.equals("/api/request") && method.equals("POST")) {
                request(ex);
            } else if (path.equals("/api/buzz") && method.equals("POST")) {
                buzz(ex);
            } else if (path.equals("/api/disconnect") && method.equals("POST")) {
                disconnect(ex);
            } else if (path.equals("/api/events") && method.equals("GET")) {
                events(ex);
            } else if (path.equals("/api/cert") && method.equals("GET")) {
                cert(ex);
            } else {
                json(ex, 404, err("Tidak ditemukan."));
            }
        } catch (Exception e) {
            ClientLogger.log("[WEB] ERROR " + e);
            try { json(ex, 500, err("Kesalahan bridge: " + e.getMessage())); } catch (IOException ignored) {}
        } finally {
            ex.close();
        }
    }

    /** Tolak permintaan lintas-situs (halaman lain tidak boleh mengendalikan bridge lokal). */
    private static boolean sameOrigin(HttpExchange ex) {
        String origin = ex.getRequestHeaders().getFirst("Origin");
        if (origin == null) return true;
        String host = ex.getRequestHeaders().getFirst("Host");
        return host != null && origin.endsWith("//" + host);
    }

    // --------------------------------------------------------------- endpoints

    private static void connect(HttpExchange ex) throws Exception {
        JsonObject b = body(ex);
        final Session s = new Session();
        SESSIONS.put(s.id, s);
        final CompletableFuture<JsonObject> f = new CompletableFuture<JsonObject>();
        s.conn.connect(b.get("host").getAsString(), b.get("tcp").getAsInt(), b.get("udp").getAsInt(),
            b.get("name").getAsString(), new NetQuizConnection.ConnectHandler() {
                @Override public void onResult(boolean ok, String message, boolean udpOk) {
                    JsonObject o = new JsonObject();
                    o.addProperty("ok", ok);
                    o.addProperty("message", message);
                    o.addProperty("udpOk", udpOk);
                    f.complete(o);
                }
            });
        JsonObject r;
        try { r = f.get(30, TimeUnit.SECONDS); }
        catch (Exception e) { r = new JsonObject(); r.addProperty("ok", false); r.addProperty("message", "Waktu habis."); }
        if (r.get("ok").getAsBoolean()) r.addProperty("sid", s.id);
        else SESSIONS.remove(s.id);
        json(ex, 200, r);
    }

    private static void request(HttpExchange ex) throws Exception {
        Session s = session(ex);
        if (s == null) return;
        String cmd = body(ex).get("cmd").getAsString().replaceAll("[\\r\\n]+", " ").trim();
        final CompletableFuture<String> f = new CompletableFuture<String>();
        s.conn.request(cmd, new NetQuizConnection.ReplyHandler() {
            @Override public void onReply(String reply) { f.complete(reply == null ? "" : reply); }
        });
        replyJson(ex, f.get(30, TimeUnit.SECONDS));
    }

    private static void buzz(HttpExchange ex) throws Exception {
        Session s = session(ex);
        if (s == null) return;
        final CompletableFuture<String> f = new CompletableFuture<String>();
        s.conn.buzz(new NetQuizConnection.ReplyHandler() {
            @Override public void onReply(String reply) { f.complete(reply == null ? "" : reply); }
        });
        replyJson(ex, f.get(30, TimeUnit.SECONDS));
    }

    private static void disconnect(HttpExchange ex) throws IOException {
        String sid = query(ex, "sid");
        Session s = sid == null ? null : SESSIONS.remove(sid);
        if (s != null) s.conn.disconnect();
        json(ex, 200, ok());
    }

    /** Server-Sent Events: meneruskan PUSH, TICK, log, dan putus-koneksi ke browser. */
    private static void events(HttpExchange ex) throws Exception {
        Session s = session(ex);
        if (s == null) return;
        ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.sendResponseHeaders(200, 0);
        OutputStream out = ex.getResponseBody();
        try {
            while (SESSIONS.containsKey(s.id)) {
                String e = s.events.poll(15, TimeUnit.SECONDS);
                String frame = e == null ? ": ping\n\n" : "data: " + e + "\n\n";
                out.write(frame.getBytes(StandardCharsets.UTF_8));
                out.flush();
                if (e != null && e.startsWith("{\"t\":\"disc\"")) break;
            }
        } catch (IOException gone) {
            // tab ditutup: lepas koneksi NetQuiz agar server menandai pemain offline
        } finally {
            SESSIONS.remove(s.id);
            s.conn.disconnect();
        }
    }

    /** Unduh sertifikat lewat soket data biner (Pilar 3), lalu serahkan PDF ke browser. */
    private static void cert(HttpExchange ex) throws Exception {
        Session s = session(ex);
        if (s == null) return;
        Path dir = Files.createTempDirectory("nq-cert");
        try {
            final CompletableFuture<String[]> f = new CompletableFuture<String[]>();
            s.conn.downloadCertificate(dir, new NetQuizConnection.DownloadHandler() {
                @Override public void onResult(boolean ok, String message) { f.complete(new String[]{ok ? "1" : "0", message}); }
            });
            String[] r = f.get(60, TimeUnit.SECONDS);
            if (!"1".equals(r[0])) { json(ex, 502, err(r[1])); return; }
            Path file = null;
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
                for (Path p : ds) { file = p; break; }
            }
            if (file == null) { json(ex, 502, err("Berkas tidak ditemukan.")); return; }
            ex.getResponseHeaders().set("Content-Disposition",
                "attachment; filename=\"" + file.getFileName().toString().replace("\"", "") + "\"");
            bytes(ex, 200, "application/pdf", Files.readAllBytes(file));
        } finally {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
                for (Path p : ds) Files.deleteIfExists(p);
            } catch (IOException ignored) {}
            Files.deleteIfExists(dir);
        }
    }

    // ----------------------------------------------------------------- helpers

    private static Session session(HttpExchange ex) throws IOException {
        String sid = query(ex, "sid");
        Session s = sid == null ? null : SESSIONS.get(sid);
        if (s == null) json(ex, 404, err("Sesi tidak ditemukan. Silakan masuk lagi."));
        return s;
    }

    private static String query(HttpExchange ex, String key) {
        String q = ex.getRequestURI().getRawQuery();
        if (q == null) return null;
        for (String kv : q.split("&")) {
            if (kv.startsWith(key + "=")) return kv.substring(key.length() + 1);
        }
        return null;
    }

    private static JsonObject body(HttpExchange ex) throws IOException {
        InputStream in = ex.getRequestBody();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n, total = 0;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > 64 * 1024) throw new IOException("Permintaan terlalu besar.");
            bo.write(buf, 0, n);
        }
        return new JsonParser().parse(new String(bo.toByteArray(), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static JsonObject err(String m) { JsonObject o = new JsonObject(); o.addProperty("error", m); return o; }
    private static JsonObject ok() { JsonObject o = new JsonObject(); o.addProperty("ok", true); return o; }

    private static void replyJson(HttpExchange ex, String reply) throws IOException {
        JsonObject o = new JsonObject();
        o.addProperty("reply", reply.isEmpty() ? null : reply);
        json(ex, 200, o);
    }

    private static void json(HttpExchange ex, int code, JsonObject o) throws IOException {
        bytes(ex, code, "application/json; charset=utf-8", GSON.toJson(o).getBytes(StandardCharsets.UTF_8));
    }

    private static void bytes(HttpExchange ex, int code, String type, byte[] data) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(code, data.length);
        try (OutputStream out = ex.getResponseBody()) { out.write(data); }
    }
}
