package server;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Melayani satu klien (dijalankan oleh thread pool - Pilar 1).
 * Protokol teks (Pilar 2):
 *   LOGIN <nama> | LIST | BROADCAST <pesan> | MSG <nama> <pesan> | REQ_QUESTION [kat] [sulit]
 *   JOIN_LOBBY | LEAVE_LOBBY | LOBBY_MSG <pesan> | LOBBY_SET <kat> <sulit> | START | ANSWER <A-D> | SCOREBOARD | CERT | HELP | EXIT
 * Balasan langsung berawalan kode 3 digit (200/400/...), sedangkan pesan asinkron
 * dari server berawalan "PUSH " sehingga mudah dipisah oleh Background Receiver Thread klien.
 */
public class ClientHandler implements Runnable {
    private static final int MAX_LINE = 1024;
    private static final long POLL_MS = 15_000;
    private static final long UDP_DEAD_MS = 30_000;       // tanpa heartbeat UDP selama ini -> putus
    private static final long TCP_IDLE_MS = 300_000;      // klien tanpa UDP: batas idle
    private static final Pattern NAME_RE = Pattern.compile("^[A-Za-z0-9_]{3,16}$");

    private final Socket socket;
    private final int clientId;
    private final Object writeLock = new Object();

    private BufferedReader reader;
    private PrintWriter writer;
    private final StringBuilder lineBuf = new StringBuilder();

    private volatile String username = null;
    private volatile long lastActivity = System.currentTimeMillis();
    private Question soloQuestion = null;                  // hanya diakses oleh thread handler ini

    public ClientHandler(Socket socket, int id) {
        this.socket = socket;
        this.clientId = id;
    }

    public String getUsername() { return username; }

    /** Thread-safe: dipanggil dari handler lain (broadcast), timer, dsb. */
    public boolean send(String line) {
        synchronized (writeLock) {
            if (writer == null) return false;
            writer.println(line);
            writer.flush();
            if (writer.checkError()) {                     // PrintWriter menelan IOException -> cek manual
                closeQuietly();
                return false;
            }
            return true;
        }
    }

    private void closeQuietly() {
        try { socket.close(); } catch (IOException ignored) {}
    }

    @Override
    public void run() {
        try {
            socket.setSoTimeout((int) POLL_MS);
            reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            synchronized (writeLock) {
                writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
            }
            ServerLogger.log("Klien #" + clientId + " terhubung dari " + socket.getRemoteSocketAddress());
            send("220 Welcome to NetQuiz Arena. Ketik LOGIN <nama> (3-16 huruf/angka/_), atau HELP");

            while (true) {
                String line;
                try {
                    line = readBoundedLine();
                } catch (SocketTimeoutException e) {
                    if (isIdleTooLong()) {
                        send("408 ERR Tidak ada aktivitas/heartbeat. Koneksi ditutup.");
                        ServerLogger.log("Klien #" + clientId + " (" + username + ") di-timeout.");
                        break;
                    }
                    continue;
                }
                if (line == null) break;                   // klien menutup koneksi
                lastActivity = System.currentTimeMillis();
                line = line.trim();
                if (line.isEmpty()) continue;
                ServerLogger.log("[Klien #" + clientId + (username != null ? "/" + username : "") + "] " + line);

                try {
                    if (!dispatch(line)) break;
                } catch (Exception e) {                    // error logika TIDAK boleh mematikan thread
                    ServerLogger.log("[ERROR] Klien #" + clientId + " perintah '" + line + "': " + e);
                    send("500 ERR Kesalahan internal server.");
                }
            }
        } catch (IOException e) {
            ServerLogger.log("[DISCONNECT] Klien #" + clientId + " (" + username + ") terputus: " + e.getMessage());
        } catch (Exception e) {
            ServerLogger.log("[ERROR] Handler klien #" + clientId + ": " + e);
        } finally {
            ServerState.unregisterUser(username, this);    // bersihkan registry (anti pemain "hantu")
            closeQuietly();
            ServerLogger.log("Soket Klien #" + clientId + " ditutup. Pemain online: " + ServerState.users.size());
        }
    }

    /** Baca satu baris dengan batas panjang; sisa baris parsial dipertahankan saat timeout. */
    private String readBoundedLine() throws IOException {
        while (true) {
            int ch = reader.read();
            if (ch == -1) {
                if (lineBuf.length() == 0) return null;
                String s = lineBuf.toString();
                lineBuf.setLength(0);
                return s;
            }
            if (ch == '\n') {
                String s = lineBuf.toString();
                lineBuf.setLength(0);
                return s;
            }
            if (ch != '\r') lineBuf.append((char) ch);
            if (lineBuf.length() > MAX_LINE) {
                lineBuf.setLength(0);
                throw new IOException("Baris melebihi " + MAX_LINE + " karakter");
            }
        }
    }

    private boolean isIdleTooLong() {
        long now = System.currentTimeMillis();
        String u = username;
        if (u != null && ServerState.udpEndpoints.containsKey(u)) {
            Long hb = ServerState.lastHeartbeat.get(u);
            long last = Math.max(lastActivity, hb == null ? 0 : hb);
            return now - last > UDP_DEAD_MS;
        }
        return now - lastActivity > TCP_IDLE_MS;
    }

    // ---------------------------------------------------------------- protokol

    /** @return false bila sesi harus diakhiri. */
    private boolean dispatch(String line) throws IOException {
        String[] p = line.split("\\s+", 2);
        String cmd = p[0].toUpperCase();
        String arg = p.length > 1 ? p[1].trim() : "";

        if (cmd.equals("EXIT") || cmd.equals("QUIT")) {
            send("200 BYE Sesi Berhasil Ditutup.");
            return false;
        }
        if (cmd.equals("HELP")) {
            send("200 HELP LOGIN <nama> || LIST || BROADCAST <pesan> || MSG <nama> <pesan> || REQ_QUESTION [kategori] [easy|medium|hard|any] "
                    + "|| JOIN_LOBBY || LEAVE_LOBBY || LOBBY_MSG <pesan> || LOBBY_SET <kategori> <kesulitan> || START || ANSWER <A-D> || SCOREBOARD || MYSCORE || CERT || EXIT");
            return true;
        }
        if (cmd.equals("LOGIN")) {
            doLogin(arg);
            return true;
        }
        if (username == null) {
            send("401 ERR Silakan LOGIN terlebih dahulu.");
            return true;
        }

        switch (cmd) {
            case "LIST":       doList(); break;
            case "BROADCAST":  doBroadcast(arg); break;
            case "MSG":        doPrivate(arg); break;
            case "REQ_QUESTION": doSoloQuestion(arg); break;
            case "LOBBY_MSG":  doLobbyMessage(arg); break;
            case "JOIN_LOBBY": doJoinLobby(); break;
            case "LEAVE_LOBBY": doLeaveLobby(); break;
            case "LOBBY_SET":  doLobbySet(arg); break;
            case "START":      doStartRound(); break;
            case "ANSWER":     doAnswer(arg); break;
            case "SCOREBOARD": send("200 SCORES " + ServerState.scoreboardText()); break;
            case "MYSCORE":    send("200 SOLO " + ServerState.getSoloScore(username)); break;
            case "CERT":       doCertificate(); break;
            default:           send("400 ERR Perintah tidak dikenal. Ketik HELP.");
        }
        return true;
    }

    private void doLogin(String name) {
        if (username != null) { send("400 ERR Anda sudah login sebagai " + username); return; }
        if (!NAME_RE.matcher(name).matches()) {
            send("400 ERR Nama harus 3-16 karakter: huruf, angka, atau underscore.");
            return;
        }
        if (!ServerState.registerUser(name, this)) {
            send("409 ERR Nama '" + name + "' sudah dipakai pemain lain.");
            return;
        }
        username = name;
        ServerLogger.log("Klien #" + clientId + " login sebagai " + name);
        send("200 SUCCESS Login Berhasil. Halo, " + name);
        ServerState.broadcast("PUSH JOIN " + name + " bergabung ke arena.", name);
    }

    private void doList() {
        StringBuilder sb = new StringBuilder();
        for (String u : ServerState.users.keySet()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(u);
            if (ServerState.lobby.contains(u)) sb.append("(lobby)");
        }
        send("200 USERS " + ServerState.users.size() + " online: " + sb);
    }

    private void doBroadcast(String msg) {
        if (msg.isEmpty()) { send("400 ERR Format: BROADCAST <pesan>"); return; }
        ServerState.broadcast("PUSH BROADCAST " + username + ": " + msg, username);
        send("200 SENT Pesan disiarkan ke " + Math.max(0, ServerState.users.size() - 1) + " pemain.");
    }

    private void doPrivate(String arg) {
        String[] p = arg.split("\\s+", 2);
        if (p.length < 2 || p[0].isEmpty() || p[1].trim().isEmpty()) {
            send("400 ERR Format: MSG <nama> <pesan>");
            return;
        }
        ClientHandler target = ServerState.users.get(p[0]);
        if (target == null) { send("404 ERR Pemain '" + p[0] + "' tidak online."); return; }
        target.send("PUSH PRIVATE dari " + username + ": " + p[1].trim());
        send("200 SENT Pesan privat terkirim ke " + p[0]);
    }

    /** Parse "[kategori] [kesulitan]"; null bila format salah. Hasil: {kategori, kesulitan}. */
    private Object[] parseQuestionArgs(String arg) {
        String[] parts = arg.isEmpty() ? new String[0] : arg.split("\\s+");
        if (parts.length > 2) return null;
        int category = TriviaApiFetcher.CATEGORY_ANY;
        String difficulty = "any";
        if (parts.length >= 1) {
            try { category = Integer.parseInt(parts[0]); } catch (NumberFormatException e) { return null; }
            if (!TriviaApiFetcher.isValidCategory(category)) return null;
        }
        if (parts.length == 2) {
            difficulty = parts[1].toLowerCase();
            if (!TriviaApiFetcher.isValidDifficulty(difficulty)) return null;
        }
        return new Object[]{category, difficulty};
    }

    private void doSoloQuestion(String arg) {
        if (ServerState.lobby.contains(username)) {
            send("409 ERR Soal solo tidak tersedia saat berada di lobby. Keluar lobby dulu.");
            return;
        }
        Object[] a = parseQuestionArgs(arg);
        if (a == null) {
            send("400 ERR Format: REQ_QUESTION [kategori 0 atau 9-32] [easy|medium|hard|any]");
            return;
        }
        Question q = QuestionBank.next((Integer) a[0], (String) a[1]);
        soloQuestion = q;
        ServerLogger.log("Klien #" + clientId + " solo, sumber=" + q.source + ", kunci=" + q.correctText());
        send("200 QUESTION " + q.format() + " || (Ketik: ANSWER <A/B/C/D>)");
    }

    /** LOBBY_MSG <pesan>: chat hanya untuk anggota lobby (selain pengirim). */
    private void doLobbyMessage(String msg) {
        if (!ServerState.lobby.contains(username)) { send("403 ERR Masuk lobby dulu (JOIN_LOBBY)."); return; }
        if (msg.isEmpty()) { send("400 ERR Format: LOBBY_MSG <pesan>"); return; }
        int sent = 0;
        for (String n : ServerState.lobby) {
            if (n.equals(username)) continue;
            ClientHandler h = ServerState.users.get(n);
            if (h != null && h.send("PUSH LOBBYCHAT " + username + ": " + msg)) sent++;
        }
        send("200 SENT Pesan lobby terkirim ke " + sent + " pemain.");
    }

    private void doJoinLobby() {
        String err = ServerState.joinLobby(username);
        if (err != null) { send(err); return; }
        ServerLogger.log(username + " masuk lobby (" + ServerState.lobby.size() + " pemain)");
        send("200 LOBBY " + ServerState.lobbyState());
    }

    private void doLeaveLobby() {
        if (!ServerState.leaveLobby(username)) { send("400 ERR Anda tidak berada di lobby."); return; }
        ServerLogger.log(username + " keluar lobby (" + ServerState.lobby.size() + " pemain)");
        send("200 LEFT Anda keluar dari lobby.");
    }

    /** LOBBY_SET <kategori> <kesulitan>: berlaku untuk semua anggota lobby (disiarkan lewat PUSH LOBBY). */
    private void doLobbySet(String arg) {
        Object[] a = arg.split("\\s+").length == 2 ? parseQuestionArgs(arg) : null;
        if (a == null) {
            send("400 ERR Format: LOBBY_SET <kategori 0 atau 9-32> <easy|medium|hard|any>");
            return;
        }
        String err = ServerState.setLobbyConfig(username, (Integer) a[0], (String) a[1]);
        send(err != null ? err : "200 SENT Pengaturan lobby diperbarui.");
    }

    private void doStartRound() {
        if (!ServerState.lobby.contains(username)) { send("403 ERR Masuk lobby dulu (JOIN_LOBBY)."); return; }
        if (ServerState.activeRound() != null) { send("409 ERR Sudah ada ronde yang berjalan."); return; }
        if (ServerState.lobby.size() < 2) {
            send("409 ERR Multiplayer tidak dapat dimulai karena hanya ada satu pemain di lobby.");
            return;
        }
        Question q = QuestionBank.next(ServerState.lobbyCategory, ServerState.lobbyDifficulty);
        QuizRound r = ServerState.startRound(q, 20);
        if (r == null) { send("409 ERR Sudah ada ronde yang berjalan."); return; }
        send("200 ROUND_STARTED Ronde dimulai oleh " + username);
    }

    private void doAnswer(String arg) {
        QuizRound round = ServerState.activeRound();
        if (round != null && ServerState.lobby.contains(username)) {   // mode multiplayer (anggota lobby)
            int idx = round.getQuestion().letterToIndex(arg);
            if (idx == -1) { send("400 ERR Pilihan tidak valid! Pilih A, B, C, atau D."); return; }
            send(round.answer(username, idx));
            return;
        }
        // mode solo
        if (soloQuestion == null) {
            send("400 ERR Belum ada soal. Gunakan REQ_QUESTION atau START dahulu.");
            return;
        }
        int idx = soloQuestion.letterToIndex(arg);
        if (idx == -1) { send("400 ERR Pilihan tidak valid! Pilih A, B, C, atau D."); return; }
        Question q = soloQuestion;
        soloQuestion = null;                               // satu soal hanya bisa dijawab sekali
        if (idx == q.correctIndex) {
            int total = ServerState.addSoloScore(username, 10);
            send("200 CORRECT Jawaban Benar! (+10 Poin). Total Skor Solo: " + total);
        } else {
            send("200 WRONG Jawaban Salah! Kunci: " + q.correctText() + ". Total Skor Solo: " + ServerState.getSoloScore(username));
        }
    }

    /**
     * PILAR 3 - Binary File Transfer & Chunking.
     * Soket data TERPISAH dari soket protokol utama agar soket utama tidak perlu ditutup:
     * header (nama + ukuran) -> data per 4KB -> shutdownOutput() -> tunggu ACK ukuran dari klien.
     */
    private void doCertificate() {
        byte[] pdf;
        String fname = "Sertifikat_" + username + ".pdf";
        try {
            pdf = CertificateGenerator.generate(username, ServerState.getScore(username),
                    ServerState.rankOf(username), ServerState.scores.size());
        } catch (Exception e) {
            ServerLogger.log("[ERROR] Gagal membuat sertifikat: " + e);
            send("500 ERR Gagal membuat sertifikat.");
            return;
        }

        try (ServerSocket dataServer = new ServerSocket(0)) {
            dataServer.setSoTimeout(10_000);
            send("200 FILE " + dataServer.getLocalPort() + " " + pdf.length + " " + fname);

            try (Socket data = dataServer.accept()) {
                data.setSoTimeout(10_000);
                DataOutputStream out = new DataOutputStream(new BufferedOutputStream(data.getOutputStream(), 8192));
                DataInputStream in = new DataInputStream(data.getInputStream());

                out.writeUTF(fname);                       // header metadata
                out.writeLong(pdf.length);
                final int CHUNK = 4096;
                int sent = 0;
                while (sent < pdf.length) {                // chunking 4KB
                    int n = Math.min(CHUNK, pdf.length - sent);
                    out.write(pdf, sent, n);
                    sent += n;
                }
                out.flush();
                data.shutdownOutput();                     // sinyal EOF; soket protokol utama tetap terbuka

                String ack = in.readUTF();                 // verifikasi ukuran dari klien
                boolean ok = ack.equals("OK " + pdf.length);
                ServerLogger.log("[FILE] " + fname + " (" + pdf.length + " byte) -> " + username
                        + (ok ? " terverifikasi" : " ACK tidak cocok: " + ack));
            }
        } catch (SocketTimeoutException e) {
            ServerLogger.log("[FILE] Klien " + username + " tidak terhubung ke soket data (timeout).");
        } catch (IOException e) {
            ServerLogger.log("[FILE] Transfer ke " + username + " gagal: " + e.getMessage());
        }
    }
}
