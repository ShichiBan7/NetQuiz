package server;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * NetQuiz Arena Server.
 * PILAR 1: Thread Pool (Executors.newFixedThreadPool) + shared state thread-safe (ServerState).
 */
public class NetQuizServer {
    public static final int PORT = 12345;
    public static final int UDP_PORT = 12346;
    public static final int MAX_CLIENTS = 20;

    public static void main(String[] args) {
        ServerLogger.log("=== MEMULAI NETQUIZ SERVER ===");
        try {
            InetAddress local = InetAddress.getLocalHost();
            ServerLogger.log("Server Hostname: " + local.getHostName() + " | IP: " + local.getHostAddress());
        } catch (IOException e) {
            ServerLogger.log("[WARN] Tidak dapat membaca hostname lokal: " + e.getMessage());
        }

        final ServerSocket serverSocket = createServerSocket();
        if (serverSocket == null) return;

        final ExecutorService pool = Executors.newFixedThreadPool(MAX_CLIENTS);
        final UdpService udp;
        try {
            udp = new UdpService(UDP_PORT);
        } catch (SocketException e) {
            ServerLogger.log("[FATAL] Port UDP " + UDP_PORT + " gagal dibuka: " + e.getMessage());
            return;
        }
        ServerState.udp = udp;
        Thread udpThread = new Thread(udp, "udp-service");
        udpThread.setDaemon(true);
        udpThread.start();

        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override public void run() {
                ServerLogger.log("[SERVER] Shutdown: menutup soket dan thread pool...");
                try { serverSocket.close(); } catch (IOException ignored) {}
                udp.close();
                pool.shutdownNow();
                try { pool.awaitTermination(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
            }
        }));

        ServerLogger.log("[SERVER] TCP port " + PORT + " | UDP port " + UDP_PORT + " | thread pool = " + MAX_CLIENTS);

        final AtomicInteger clientCounter = new AtomicInteger();
        final AtomicInteger active = new AtomicInteger();

        while (!serverSocket.isClosed()) {
            try {                                          // try/catch DI DALAM loop: 1 error tidak mematikan server
                Socket s = serverSocket.accept();
                if (active.get() >= MAX_CLIENTS) {
                    reject(s);
                    continue;
                }
                active.incrementAndGet();
                final ClientHandler handler = new ClientHandler(s, clientCounter.incrementAndGet());
                try {
                    pool.execute(new Runnable() {
                        @Override public void run() {
                            try { handler.run(); } finally { active.decrementAndGet(); }
                        }
                    });
                } catch (RejectedExecutionException e) {
                    active.decrementAndGet();
                    reject(s);
                }
            } catch (IOException e) {
                if (serverSocket.isClosed()) break;
                ServerLogger.log("[WARN] accept() gagal: " + e.getMessage());
                try { Thread.sleep(100); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            } catch (RuntimeException e) {
                ServerLogger.log("[ERROR] Kesalahan tak terduga di accept-loop: " + e);
            }
        }
    }

    private static void reject(Socket s) {
        try {
            s.getOutputStream().write("503 ERR Server penuh, coba lagi nanti.\n".getBytes("UTF-8"));
            s.getOutputStream().flush();
        } catch (IOException ignored) {
        } finally {
            try { s.close(); } catch (IOException ignored) {}
        }
        ServerLogger.log("[SERVER] Koneksi ditolak: server penuh.");
    }

    /** Membuat ServerSocket dengan SO_REUSEADDR; mengembalikan null bila port gagal di-bind. */
    private static ServerSocket createServerSocket() {
        try {
            ServerSocket ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(PORT));
            return ss;
        } catch (IOException e) {
            ServerLogger.log("[FATAL] Server gagal berjalan di port " + PORT + ": " + e.getMessage());
            return null;
        }
    }
}
