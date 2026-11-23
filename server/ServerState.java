package server;

import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * PILAR 1 - State bersama (shared registry) yang thread-safe.
 * Seluruh koleksi memakai ConcurrentHashMap / AtomicInteger; skor diubah secara atomik.
 */
public final class ServerState {
    private ServerState() {}

    /** username -> handler (pemain online). putIfAbsent menjamin username unik secara atomik. */
    public static final ConcurrentHashMap<String, ClientHandler> users = new ConcurrentHashMap<>();
    /** username -> skor MULTIPLAYER (leaderboard & sertifikat; tetap tersimpan walau pemain keluar). */
    public static final ConcurrentHashMap<String, AtomicInteger> scores = new ConcurrentHashMap<>();
    /** username -> skor SOLO (pribadi, terpisah dari leaderboard multiplayer). */
    public static final ConcurrentHashMap<String, AtomicInteger> soloScores = new ConcurrentHashMap<>();
    /** username -> alamat UDP (didaftarkan klien lewat paket REG). */
    public static final ConcurrentHashMap<String, SocketAddress> udpEndpoints = new ConcurrentHashMap<>();
    /** username -> waktu heartbeat UDP terakhir (ms). */
    public static final ConcurrentHashMap<String, Long> lastHeartbeat = new ConcurrentHashMap<>();

    /** Pemain yang berada di lobby multiplayer (hanya mereka ikut ronde multi). */
    public static final Set<String> lobby = ConcurrentHashMap.newKeySet();
    /** Pengaturan soal lobby yang dipakai bersama seluruh anggota lobby. */
    public static volatile int lobbyCategory = TriviaApiFetcher.CATEGORY_ANY;
    public static volatile String lobbyDifficulty = "any";

    public static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "scheduler");
        t.setDaemon(true);
        return t;
    });

    public static volatile UdpService udp;
    private static QuizRound currentRound;   // dilindungi synchronized(ServerState.class)

    // ---------------------------------------------------------------- pengguna

    public static boolean registerUser(String name, ClientHandler h) {
        if (users.putIfAbsent(name, h) != null) return false;
        scores.putIfAbsent(name, new AtomicInteger(0));
        soloScores.putIfAbsent(name, new AtomicInteger(0));
        return true;
    }

    public static void unregisterUser(String name, ClientHandler h) {
        if (name == null) return;
        if (users.remove(name, h)) {              // hanya hapus bila masih milik handler ini
            udpEndpoints.remove(name);
            lastHeartbeat.remove(name);
            leaveLobby(name);
            broadcast("PUSH LEAVE " + name + " keluar dari arena.", null);
        }
    }

    /** Kirim satu baris ke semua pemain online (kecuali exceptUser). Tidak pernah melempar exception. */
    public static void broadcast(String line, String exceptUser) {
        for (ClientHandler h : users.values()) {
            if (exceptUser != null && exceptUser.equals(h.getUsername())) continue;
            h.send(line);
        }
    }

    /** Kirim satu baris hanya ke anggota lobby. */
    public static void broadcastLobby(String line) {
        for (String n : lobby) {
            ClientHandler h = users.get(n);
            if (h != null) h.send(line);
        }
    }

    // ------------------------------------------------------------------- lobby

    /** Pemain di luar lobby diberi tahu bahwa isi lobby berubah (untuk menyegarkan daftar pemain). */
    private static void notifyOutsideLobby() {
        for (ClientHandler h : users.values()) {
            String n = h.getUsername();
            if (n == null || !lobby.contains(n)) h.send("PUSH LOBBYINFO");
        }
    }

    /** "a,b,c || kategori || kesulitan" - dikirim ke anggota agar tampilan selalu sinkron. */
    public static String lobbyState() {
        List<String> names = new ArrayList<>(lobby);
        Collections.sort(names);
        return String.join(",", names) + " || " + lobbyCategory + " || " + lobbyDifficulty;
    }

    /** @return null bila berhasil, atau baris galat lengkap. Atomik terhadap startRound (monitor kelas). */
    public static synchronized String joinLobby(String name) {
        if (activeRound() != null) return "409 ERR Ronde sedang berlangsung. Coba lagi setelah ronde selesai.";
        if (!lobby.add(name)) return "400 ERR Anda sudah berada di lobby.";
        broadcastLobby("PUSH LOBBY " + lobbyState() + " || " + name + " masuk lobby.");
        notifyOutsideLobby();
        return null;
    }

    /** Keluar lobby (juga dipakai saat pemain terputus). @return true bila memang sedang di lobby. */
    public static boolean leaveLobby(String name) {
        if (!lobby.remove(name)) return false;
        QuizRound r = getRound();
        if (r != null) r.onUserLeft(name);
        synchronized (ServerState.class) {
            if (lobby.isEmpty()) {                 // lobby kosong -> kembali ke pengaturan awal
                lobbyCategory = TriviaApiFetcher.CATEGORY_ANY;
                lobbyDifficulty = "any";
            } else {
                broadcastLobby("PUSH LOBBY " + lobbyState() + " || " + name + " keluar dari lobby.");
            }
        }
        notifyOutsideLobby();
        return true;
    }

    /** Ubah kategori/kesulitan untuk SELURUH lobby. @return null bila berhasil, atau baris galat. */
    public static synchronized String setLobbyConfig(String user, int category, String difficulty) {
        if (!lobby.contains(user)) return "403 ERR Masuk lobby dulu (JOIN_LOBBY).";
        if (activeRound() != null) return "409 ERR Pengaturan tidak bisa diubah saat ronde berlangsung.";
        lobbyCategory = category;
        lobbyDifficulty = difficulty;
        broadcastLobby("PUSH LOBBY " + lobbyState() + " || " + user + " mengubah pengaturan soal.");
        return null;
    }

    // -------------------------------------------------------------------- skor

    /** Ubah skor secara atomik (minimum 0). Mengembalikan skor baru. */
    public static int addScore(String name, int delta) {
        AtomicInteger a = scores.computeIfAbsent(name, k -> new AtomicInteger(0));
        return a.updateAndGet(v -> Math.max(0, v + delta));
    }

    public static int addSoloScore(String name, int delta) {
        AtomicInteger a = soloScores.computeIfAbsent(name, k -> new AtomicInteger(0));
        return a.updateAndGet(v -> Math.max(0, v + delta));
    }

    public static int getSoloScore(String name) {
        AtomicInteger a = soloScores.get(name);
        return a == null ? 0 : a.get();
    }

    public static int getScore(String name) {
        AtomicInteger a = scores.get(name);
        return a == null ? 0 : a.get();
    }

    public static class ScoreEntry {
        public final String name;
        public final int score;
        ScoreEntry(String name, int score) { this.name = name; this.score = score; }
    }

    /** Snapshot terurut (nilai disalin dulu agar comparator tidak terganggu perubahan konkuren). */
    public static List<ScoreEntry> ranking() {
        List<ScoreEntry> list = new ArrayList<>();
        for (java.util.Map.Entry<String, AtomicInteger> e : scores.entrySet()) {
            list.add(new ScoreEntry(e.getKey(), e.getValue().get()));
        }
        Collections.sort(list, new Comparator<ScoreEntry>() {
            @Override public int compare(ScoreEntry a, ScoreEntry b) {
                int c = Integer.compare(b.score, a.score);
                return c != 0 ? c : a.name.compareTo(b.name);
            }
        });
        return list;
    }

    public static int rankOf(String name) {
        List<ScoreEntry> r = ranking();
        for (int i = 0; i < r.size(); i++) if (r.get(i).name.equals(name)) return i + 1;
        return r.size();
    }

    /** "1. andi: 20 poin (online) || 2. budi: 10 poin" */
    public static String scoreboardText() {
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (ScoreEntry e : ranking()) {
            if (sb.length() > 0) sb.append(" || ");
            sb.append(i++).append(". ").append(e.name).append(": ").append(e.score).append(" poin");
            if (users.containsKey(e.name)) sb.append(" (online)");
        }
        return sb.length() == 0 ? "(belum ada pemain)" : sb.toString();
    }

    // ------------------------------------------------------------------- ronde

    public static synchronized QuizRound startRound(Question q, int seconds) {
        if (currentRound != null && currentRound.isActive()) return null;
        currentRound = new QuizRound(q, seconds);
        currentRound.begin();
        return currentRound;
    }

    public static synchronized QuizRound getRound() {
        return currentRound;
    }

    /** Ronde aktif saat ini, atau null. */
    public static QuizRound activeRound() {
        QuizRound r = getRound();
        return (r != null && r.isActive()) ? r : null;
    }
}
