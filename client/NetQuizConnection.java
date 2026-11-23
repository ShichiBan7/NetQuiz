package client;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.*;

/**
 * Mesin jaringan klien NetQuiz untuk GUI Web. KELAS INI TIDAK MEMAKAI kode tampilan.
 * Semua operasi soket (TCP, UDP, unduh file) berjalan di background thread; hasilnya
 * dikirim lewat callback ke NetQuizWebBridge, yang meneruskannya ke browser. Dengan begitu
 * tombol di halaman web tidak pernah memblokir UI dan kode soket tidak ada di event handler.
 *
 *  - Pilar 2: Background Receiver Thread membaca balasan + pesan PUSH secara asinkron.
 *  - Pilar 3: unduh sertifikat PDF lewat soket data biner, chunk 4 KB, header nama+ukuran.
 *  - Pilar 5: heartbeat UDP, timer TICK, BUZZ dengan setSoTimeout(1000) + retransmisi 3x.
 */
public class NetQuizConnection {

    /** Callback peristiwa asinkron dari server. Dipanggil dari BACKGROUND thread. */
    public interface Listener {
        void onLog(String message);
        /** type: JOIN, LEAVE, BROADCAST, PRIVATE, QUESTION, BUZZER, BUZZER_OPEN, ROUND_END, ... */
        void onPush(String type, String[] parts);
        void onTick(int secondsLeft);
        void onDisconnected(String reason);
    }

    /** Hasil sebuah permintaan. reply == null berarti gagal/timeout/terputus. */
    public interface ReplyHandler { void onReply(String reply); }

    public interface ConnectHandler { void onResult(boolean ok, String message, boolean udpOk); }

    public interface DownloadHandler { void onResult(boolean ok, String message); }

    private static final long REPLY_TIMEOUT_MS = 20_000;
    private static final String DISCONNECTED = "__DISCONNECTED__";

    private final Listener listener;
    private final BlockingQueue<String> replies = new LinkedBlockingQueue<>();
    private final BlockingQueue<String> udpReplies = new LinkedBlockingQueue<>();
    private final ExecutorService tcpExec = daemonSingle("tcp-command");
    private final ExecutorService udpExec = daemonSingle("udp-command");
    private final Object writeLock = new Object();

    private Socket socket;
    private PrintWriter writer;
    private DatagramSocket udp;
    private InetAddress serverAddr;
    private int udpPort;
    private volatile boolean connected = false;
    private volatile boolean closing = false;
    private volatile String user;

    public NetQuizConnection(Listener listener) {
        this.listener = listener;
    }

    public boolean isConnected() { return connected; }
    public String getUser() { return user; }

    private static ExecutorService daemonSingle(final String name) {
        return Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override public Thread newThread(Runnable r) {
                Thread t = new Thread(r, name);
                t.setDaemon(true);
                return t;
            }
        });
    }

    // ---------------------------------------------------------------- koneksi

    /** Hubungkan TCP, LOGIN, lalu daftarkan UDP. Berjalan di thread sendiri. */
    public void connect(final String host, final int tcpPort, final int udpPort,
                        final String name, final ConnectHandler handler) {
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    doConnect(host, tcpPort, udpPort, name, handler);
                } catch (Exception e) {
                    closeAll();
                    handler.onResult(false, "Gagal: " + e.getMessage(), false);
                }
            }
        }, "connector");
        t.setDaemon(true);
        t.start();
    }

    private void doConnect(String host, int tcpPort, int udpPort, String name, ConnectHandler handler) throws IOException {
        closing = false;
        this.udpPort = udpPort;
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, tcpPort), 8000);
        } catch (IOException e) {
            try { s.close(); } catch (IOException ignored) {}
            handler.onResult(false, "Tidak dapat terhubung ke " + host + ":" + tcpPort + " (" + e.getMessage() + ")", false);
            return;
        }
        socket = s;
        serverAddr = s.getInetAddress();
        BufferedReader reader = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
        synchronized (writeLock) {
            writer = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true);
        }
        connected = true;
        listener.onLog("Koneksi TCP dibangun ke " + host + ":" + tcpPort);
        startReceiver(reader);

        String r = requestSync("LOGIN " + name);
        if (r == null) {
            closeAll();
            handler.onResult(false, "Server tidak merespons saat LOGIN.", false);
            return;
        }
        if (!r.startsWith("200")) {
            closeAll();
            handler.onResult(false, stripCode(r), false);
            return;
        }
        user = name;
        boolean udpOk = startUdp();                  // kegagalan UDP tidak membatalkan login
        handler.onResult(true, stripCode(r), udpOk);
    }

    /** Keluar dengan rapi: kirim EXIT, lalu tutup semua soket. */
    public void disconnect() {
        if (closing) return;
        closing = true;
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    sendRaw("EXIT");
                    Thread.sleep(250);
                } catch (InterruptedException ignored) {
                } finally {
                    closeAll();
                }
            }
        }, "disconnector");
        t.setDaemon(true);
        t.start();
    }

    private void closeAll() {
        connected = false;
        user = null;
        DatagramSocket u = udp;
        if (u != null) u.close();                    // soket UDP lama selalu ditutup
        udp = null;
        Socket s = socket;
        if (s != null) { try { s.close(); } catch (IOException ignored) {} }
        socket = null;
        replies.offer(DISCONNECTED);
    }

    // --------------------------------------------------------------- TCP I/O

    private void startReceiver(final BufferedReader reader) {
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                String why = "Koneksi ke server terputus.";
                try {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("PUSH ")) dispatchPush(line.substring(5));
                        else replies.offer(line);
                    }
                } catch (IOException e) {
                    why = "Koneksi terputus: " + e.getMessage();
                } finally {
                    boolean wasUp = connected;
                    closeAll();
                    if (wasUp && !closing) listener.onDisconnected(why);
                }
            }
        }, "tcp-receiver");
        t.setDaemon(true);
        t.start();
    }

    private void dispatchPush(String body) {
        String[] parts = body.split("\\|\\|");
        for (int i = 0; i < parts.length; i++) parts[i] = parts[i].trim();
        String first = parts[0];
        int sp = first.indexOf(' ');
        String type = sp < 0 ? first : first.substring(0, sp);
        parts[0] = sp < 0 ? "" : first.substring(sp + 1).trim();
        try {
            listener.onPush(type, parts);
        } catch (RuntimeException e) {               // callback buruk tidak boleh mematikan receiver
            listener.onLog("[ERROR] listener: " + e);
        }
    }

    private void sendRaw(String cmd) {
        synchronized (writeLock) {
            if (writer != null) { writer.println(cmd); writer.flush(); }
        }
    }

    /** Kirim perintah dan tunggu balasan langsung. null bila gagal. */
    private String requestSync(String cmd) {
        if (!connected) return null;
        replies.clear();
        synchronized (writeLock) {
            writer.println(cmd);
            writer.flush();
            if (writer.checkError()) {
                listener.onLog("[ERROR] Gagal mengirim: koneksi terputus.");
                return null;
            }
        }
        listener.onLog("> " + cmd);
        long deadline = System.currentTimeMillis() + REPLY_TIMEOUT_MS;
        try {
            while (true) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) { listener.onLog("[TIMEOUT] Server tidak merespons."); return null; }
                String r = replies.poll(left, TimeUnit.MILLISECONDS);
                if (r == null) { listener.onLog("[TIMEOUT] Server tidak merespons."); return null; }
                if (DISCONNECTED.equals(r)) return null;
                if (r.startsWith("220 ")) continue;  // banner selamat datang
                listener.onLog("< " + r);
                return r;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** Permintaan asinkron: balasan dikirim ke handler dari background thread. */
    public void request(final String cmd, final ReplyHandler handler) {
        tcpExec.execute(new Runnable() {
            @Override public void run() {
                String r = null;
                try { r = requestSync(cmd); } catch (RuntimeException e) { listener.onLog("[ERROR] " + e); }
                handler.onReply(r);
            }
        });
    }

    public static String stripCode(String reply) {
        String s = reply == null ? "" : reply.trim();
        s = s.replaceFirst("^\\d{3}\\s+", "");
        s = s.replaceFirst("^(SUCCESS|ERR|SENT|BYE)\\s+", "");
        return s;
    }

    // ------------------------------------------------------------------- UDP

    private boolean startUdp() {
        final DatagramSocket sock;
        try {
            sock = new DatagramSocket();
            sock.setSoTimeout(1000);
        } catch (SocketException e) {
            listener.onLog("[UDP] Gagal membuka soket: " + e.getMessage());
            return false;
        }
        udp = sock;

        Thread rx = new Thread(new Runnable() {
            @Override public void run() {
                byte[] buf = new byte[512];
                while (connected && !sock.isClosed()) {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    try {
                        sock.receive(p);
                        String m = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8).trim();
                        if (m.startsWith("TICK ")) {
                            try { listener.onTick(Integer.parseInt(m.substring(5).trim())); }
                            catch (NumberFormatException ignored) {}
                        } else if (!m.equals("HB_ACK")) {
                            udpReplies.offer(m);
                        }
                    } catch (SocketTimeoutException ignored) {
                    } catch (IOException e) {
                        break;
                    }
                }
            }
        }, "udp-receiver");
        rx.setDaemon(true);
        rx.start();

        String reg = udpRequest("REG " + user, "REG_");
        if (!"REG_OK".equals(reg)) {
            listener.onLog("[UDP] Registrasi gagal (" + reg + "). Buzzer/timer nonaktif.");
            sock.close();
            udp = null;
            return false;
        }
        listener.onLog("[UDP] Terdaftar. Heartbeat & timer aktif.");

        Thread hb = new Thread(new Runnable() {
            @Override public void run() {
                while (connected && !sock.isClosed()) {
                    try {
                        byte[] d = ("HB " + user).getBytes(StandardCharsets.UTF_8);
                        sock.send(new DatagramPacket(d, d.length, serverAddr, udpPort));
                        Thread.sleep(5000);
                    } catch (IOException | InterruptedException e) {
                        break;
                    }
                }
            }
        }, "udp-heartbeat");
        hb.setDaemon(true);
        hb.start();
        return true;
    }

    public boolean isUdpActive() {
        DatagramSocket u = udp;
        return u != null && !u.isClosed();
    }

    /** Kirim paket UDP, tunggu balasan berawalan prefix; retransmisi hingga 3x. */
    private String udpRequest(String msg, String prefix) {
        DatagramSocket sock = udp;
        if (sock == null || sock.isClosed()) return null;
        byte[] data = msg.getBytes(StandardCharsets.UTF_8);
        for (int attempt = 1; attempt <= 3; attempt++) {
            udpReplies.clear();
            try {
                sock.send(new DatagramPacket(data, data.length, serverAddr, udpPort));
                long deadline = System.currentTimeMillis() + 1000;
                while (System.currentTimeMillis() < deadline) {
                    String r = udpReplies.poll(deadline - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
                    if (r == null) break;
                    if (r.startsWith(prefix) || r.startsWith("ERR")) return r;
                }
                listener.onLog("[UDP] Tidak ada balasan (percobaan " + attempt + "/3), retransmisi...");
            } catch (IOException e) {
                return "ERR " + e.getMessage();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    /** Tekan buzzer lewat UDP. Balasan: BUZZ_WIN / BUZZ_LATE / BUZZ_LOCKED / BUZZ_NOROUND / null. */
    public void buzz(final ReplyHandler handler) {
        udpExec.execute(new Runnable() {
            @Override public void run() {
                String u = user;
                handler.onReply(u == null ? null : udpRequest("BUZZ " + u, "BUZZ_"));
            }
        });
    }

    // ------------------------------------------------------- unduh file biner

    /** Unduh sertifikat PDF (soket data terpisah, chunk 4 KB, header nama+ukuran, ACK ukuran). */
    public void downloadCertificate(final Path dir, final DownloadHandler handler) {
        tcpExec.execute(new Runnable() {
            @Override public void run() {
                String r = requestSync("CERT");
                if (r == null) { handler.onResult(false, "Server tidak merespons."); return; }
                if (!r.startsWith("200 FILE ")) { handler.onResult(false, stripCode(r)); return; }
                String[] p = r.split("\\s+");
                int dataPort;
                try { dataPort = Integer.parseInt(p[2]); }
                catch (RuntimeException e) { handler.onResult(false, "Balasan CERT tidak valid."); return; }

                try (Socket data = new Socket()) {
                    data.connect(new InetSocketAddress(serverAddr, dataPort), 5000);
                    data.setSoTimeout(10_000);
                    DataInputStream in = new DataInputStream(new BufferedInputStream(data.getInputStream(), 8192));
                    String name = in.readUTF();
                    long size = in.readLong();
                    if (size < 0 || size > 50L * 1024 * 1024) throw new IOException("Ukuran file tidak wajar: " + size);
                    String safe = Paths.get(name).getFileName().toString();      // cegah path traversal
                    Files.createDirectories(dir);
                    Path target = dir.resolve(safe);
                    byte[] buf = new byte[4096];
                    long received = 0;
                    try (OutputStream fos = new BufferedOutputStream(Files.newOutputStream(target))) {
                        while (received < size) {
                            int n = in.read(buf, 0, (int) Math.min(buf.length, size - received));
                            if (n == -1) throw new EOFException("Koneksi putus di " + received + "/" + size + " byte");
                            fos.write(buf, 0, n);
                            received += n;
                        }
                    }
                    in.read();                                                   // EOF dari shutdownOutput()
                    DataOutputStream out = new DataOutputStream(data.getOutputStream());
                    out.writeUTF("OK " + received);
                    out.flush();
                    handler.onResult(true, target.toAbsolutePath().toString() + " (" + received + " byte)");
                } catch (IOException e) {
                    listener.onLog("[FILE] Unduhan gagal: " + e);
                    handler.onResult(false, "Unduhan gagal: " + e.getMessage());
                }
            }
        });
    }
}
