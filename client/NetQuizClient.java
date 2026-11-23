package client;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.NoSuchElementException;
import java.util.Scanner;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Klien NetQuiz (CLI).
 *  - Background Receiver Thread membaca semua pesan server secara asinkron (Pilar 2).
 *  - Soket UDP: heartbeat, timer countdown (TICK), buzzer dengan retransmisi (Pilar 5).
 *  - Unduh sertifikat PDF lewat soket data biner + chunking 4KB (Pilar 3).
 * Pemakaian: java -cp ".;lib/gson-2.15.0.jar" client.NetQuizClient [host] [tcpPort] [udpPort]
 */
public class NetQuizClient {

    private static final String[] CATEGORY_NAMES = {
        "General Knowledge", "Books", "Film", "Music", "Musicals & Theatres",
        "Television", "Video Games", "Board Games", "Science & Nature", "Computers",
        "Mathematics", "Mythology", "Sports", "Geography", "History",
        "Politics", "Art", "Celebrities", "Animals", "Vehicles",
        "Comics", "Gadgets", "Anime & Manga", "Cartoon & Animations"
    };
    private static final int CATEGORY_ID_OFFSET = 9;
    private static final long REPLY_TIMEOUT_SEC = 20;

    private static String host = "localhost";
    private static int tcpPort = 12345;
    private static int udpPort = 12346;

    private static int selectedCategoryId = 0;
    private static String selectedCategoryName = "Acak (Semua Kategori)";
    private static String selectedDifficulty = "any";
    private static String selectedDifficultyName = "Acak (Semua Tingkat)";

    private static volatile boolean connected = true;
    private static volatile boolean exiting = false;
    private static volatile String myUser = null;

    private static final String DISCONNECTED = "__DISCONNECTED__";
    private static final BlockingQueue<String> replies = new LinkedBlockingQueue<>();   // balasan langsung server
    private static final BlockingQueue<String> udpReplies = new LinkedBlockingQueue<>(); // balasan UDP
    private static DatagramSocket udp;
    private static volatile boolean inLobby = false;
    private static InetAddress serverAddr;
    private static PrintWriter writer;

    public static void main(String[] args) {
        if (args.length >= 1) host = args[0];
        try {
            if (args.length >= 2) tcpPort = Integer.parseInt(args[1]);
            if (args.length >= 3) udpPort = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            System.out.println("[INFO] Port tidak valid, memakai default " + tcpPort + "/" + udpPort);
        }

        ClientLogger.log("Menghubungkan ke NetQuiz Server di " + host + ":" + tcpPort + "...");
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, tcpPort), 10_000);
            serverAddr = socket.getInetAddress();
            ClientLogger.log("Koneksi TCP berhasil dibangun!");

            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);

            startReceiverThread(reader);                    // <-- Background Receiver Thread

            runMenu(socket);
        } catch (SocketTimeoutException e) {
            ClientLogger.log("[EXCEPTION] Timeout saat menghubungi server.");
        } catch (IOException e) {
            ClientLogger.log("[EXCEPTION] Gangguan jaringan: " + e.getMessage());
        } finally {
            connected = false;
            if (udp != null) udp.close();
        }
        System.out.println("Aplikasi Klien NetQuiz selesai.");
    }

    // ----------------------------------------------------------- receiver TCP

    private static void startReceiverThread(final BufferedReader reader) {
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("PUSH ")) printAsync(line.substring(5));
                        else replies.offer(line);
                    }
                } catch (IOException e) {
                    if (connected && !exiting) ClientLogger.log("[RECEIVER] Koneksi terputus: " + e.getMessage());
                } finally {
                    connected = false;
                    replies.offer(DISCONNECTED);
                    if (!exiting) System.out.println("\n[INFO] Koneksi ke server terputus.");
                }
            }
        }, "tcp-receiver");
        t.setDaemon(true);
        t.start();
    }

    private static void printAsync(String body) {
        System.out.println();
        String[] parts = body.split("\\|\\|");
        System.out.println(">> " + parts[0].trim());
        for (int i = 1; i < parts.length; i++) System.out.println("   " + parts[i].trim());
    }

    // ------------------------------------------------------------------- menu

    private static void runMenu(Socket socket) {
        Scanner scanner = new Scanner(System.in);
        boolean running = true;
        try {
            while (running && connected) {
                printMainMenu();
                int choice = readInt(scanner, "Pilih menu (angka): ");
                switch (choice) {
                    case 1: running = doLogin(scanner); break;
                    case 2: chooseCategory(scanner); break;
                    case 3: chooseDifficulty(scanner); break;
                    case 4: {
                        String r = sendAndShow("REQ_QUESTION " + selectedCategoryId + " " + selectedDifficulty);
                        if (r != null && r.startsWith("200 QUESTION")) askAnswer(scanner);
                        running = connected;
                        break;
                    }
                    case 5: {
                        String r = sendAndShow("JOIN_LOBBY");
                        if (r != null && r.startsWith("200 LOBBY")) {
                            inLobby = true;
                            sendAndShow("LOBBY_SET " + selectedCategoryId + " " + selectedDifficulty);
                        }
                        break;
                    }
                    case 6: doBuzz(scanner); break;
                    case 7: askAnswer(scanner); break;
                    case 8: {
                        System.out.print("Pesan untuk semua: ");
                        String m = scanner.nextLine().trim();
                        if (!m.isEmpty()) sendAndShow("BROADCAST " + m);
                        break;
                    }
                    case 9: {
                        System.out.print("Kirim ke (nama pemain): ");
                        String to = scanner.nextLine().trim();
                        System.out.print("Pesan: ");
                        String m = scanner.nextLine().trim();
                        if (!to.isEmpty() && !m.isEmpty()) sendAndShow("MSG " + to + " " + m);
                        break;
                    }
                    case 10: sendAndShow("LIST"); break;
                    case 11: sendAndShow("SCOREBOARD"); break;
                    case 12: doDownloadCertificate(); break;
                    case 13: {
                        String r = sendAndShow("LEAVE_LOBBY");
                        if (r != null && r.startsWith("200")) inLobby = false;
                        break;
                    }
                    case 14: sendAndShow("START"); break;
                    case 16: sendAndShow("MYSCORE"); break;
                    case 15: {
                        System.out.print("Pesan untuk anggota lobby: ");
                        String m = scanner.nextLine().trim();
                        if (!m.isEmpty()) sendAndShow("LOBBY_MSG " + m);
                        break;
                    }
                    case 0:
                        exiting = true;
                        sendAndShow("EXIT");
                        try { socket.shutdownOutput(); } catch (IOException ignored) {}
                        running = false;
                        break;
                    default:
                        System.out.println("[INFO] Menu tidak tersedia. Pilih angka 0-16.");
                }
            }
        } catch (NoSuchElementException e) {                // stdin ditutup (Ctrl+D / Ctrl+Z)
            ClientLogger.log("Input ditutup, keluar dengan rapi.");
            exiting = true;
            sendQuietly("EXIT");
        }
    }

    private static void printMainMenu() {
        System.out.println("\n================ MENU NETQUIZ ================");
        System.out.println("Pemain    : " + (myUser == null ? "(belum login)" : myUser));
        System.out.println("Kategori  : " + selectedCategoryName);
        System.out.println("Kesulitan : " + selectedDifficultyName);
        System.out.println("----------------------------------------------");
        System.out.println(" 1. Login                 7. Jawab (ANSWER)");
        System.out.println(" 2. Pilih Kategori        8. Chat ke semua (BROADCAST)");
        System.out.println(" 3. Pilih Kesulitan       9. Chat privat (MSG)");
        System.out.println(" 4. Soal Solo            10. Daftar pemain online");
        System.out.println(" 5. Masuk Lobby Multi    11. Scoreboard");
        System.out.println(" 6. BUZZ! (UDP)          12. Unduh Sertifikat PDF");
        System.out.println("13. Keluar Lobby         14. Mulai Ronde (di lobby)");
        System.out.println("15. Chat ke lobby saja   16. Poin solo saya");
        System.out.println(" 0. Keluar");
    }

    private static boolean doLogin(Scanner scanner) {
        System.out.print("Masukkan nama pengguna (3-16 huruf/angka/_): ");
        String name = scanner.nextLine().trim();
        if (name.isEmpty()) { System.out.println("[INFO] Nama tidak boleh kosong."); return true; }
        String r = sendAndShow("LOGIN " + name);
        if (r != null && r.startsWith("200")) {
            myUser = name;
            startUdp();
        }
        return connected;
    }

    private static void chooseCategory(Scanner scanner) {
        System.out.println("\n--- PILIH KATEGORI ---");
        System.out.println(" 0. Acak (Semua Kategori)");
        for (int i = 0; i < CATEGORY_NAMES.length; i++) System.out.printf("%2d. %s%n", i + 1, CATEGORY_NAMES[i]);
        int c = readInt(scanner, "Pilih kategori (angka): ");
        if (c == 0) {
            selectedCategoryId = 0;
            selectedCategoryName = "Acak (Semua Kategori)";
        } else if (c >= 1 && c <= CATEGORY_NAMES.length) {
            selectedCategoryId = c - 1 + CATEGORY_ID_OFFSET;
            selectedCategoryName = CATEGORY_NAMES[c - 1];
        } else {
            System.out.println("[INFO] Pilihan tidak valid, kategori tidak diubah.");
            return;
        }
        System.out.println("[OK] Kategori: " + selectedCategoryName);
        syncLobby();
    }

    /** Bila sedang di lobby, pengaturan soal dibagikan ke seluruh anggota lobby. */
    private static void syncLobby() {
        if (inLobby) sendAndShow("LOBBY_SET " + selectedCategoryId + " " + selectedDifficulty);
    }

    private static void chooseDifficulty(Scanner scanner) {
        System.out.println("\n--- PILIH TINGKAT KESULITAN ---");
        System.out.println("1. Mudah   2. Sedang   3. Sulit   0. Acak");
        int d = readInt(scanner, "Pilih tingkat (angka): ");
        switch (d) {
            case 1: selectedDifficulty = "easy";   selectedDifficultyName = "Mudah (Easy)"; break;
            case 2: selectedDifficulty = "medium"; selectedDifficultyName = "Sedang (Medium)"; break;
            case 3: selectedDifficulty = "hard";   selectedDifficultyName = "Sulit (Hard)"; break;
            case 0: selectedDifficulty = "any";    selectedDifficultyName = "Acak (Semua Tingkat)"; break;
            default: System.out.println("[INFO] Pilihan tidak valid."); return;
        }
        System.out.println("[OK] Tingkat kesulitan: " + selectedDifficultyName);
        syncLobby();
    }

    private static void askAnswer(Scanner scanner) {
        String letter = null;
        while (letter == null) {
            int a = readInt(scanner, "Pilih jawaban (1=A, 2=B, 3=C, 4=D): ");
            if (a >= 1 && a <= 4) letter = String.valueOf((char) ('A' + a - 1));
            else System.out.println("[INFO] Pilih angka 1-4.");
        }
        sendAndShow("ANSWER " + letter);
    }

    private static int readInt(Scanner scanner, String prompt) {
        while (true) {
            System.out.print(prompt);
            String line = scanner.nextLine().trim();       // NoSuchElementException ditangani di runMenu
            try {
                return Integer.parseInt(line);
            } catch (NumberFormatException e) {
                System.out.println("[INFO] Masukkan angka saja.");
            }
        }
    }

    // -------------------------------------------------------------- kirim TCP

    private static void sendQuietly(String cmd) {
        if (writer != null) { writer.println(cmd); writer.flush(); }
    }

    /** Kirim perintah, tunggu balasan langsung (bukan PUSH) dengan timeout. Mengembalikan null bila gagal. */
    private static String sendAndShow(String command) {
        if (!connected) { System.out.println("[INFO] Tidak terhubung ke server."); return null; }
        replies.clear();
        writer.println(command);
        writer.flush();
        if (writer.checkError()) {
            connected = false;
            System.out.println("[INFO] Gagal mengirim: koneksi terputus.");
            return null;
        }
        ClientLogger.log("Mengirim perintah: " + command);

        String response;
        try {
            response = replies.poll(REPLY_TIMEOUT_SEC, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        if (response == null) {
            System.out.println("[TIMEOUT] Server tidak merespons dalam " + REPLY_TIMEOUT_SEC + " detik.");
            return null;
        }
        if (DISCONNECTED.equals(response)) return null;

        System.out.println("\n[SERVER RESPONSE]:");
        String[] parts = response.split("\\|\\|");
        for (String part : parts) System.out.println("  " + part.trim());
        return response;
    }

    // -------------------------------------------------------------------- UDP

    private static void startUdp() {
        try {
            if (udp != null) udp.close();
            udp = new DatagramSocket();
            udp.setSoTimeout(1000);
        } catch (SocketException e) {
            ClientLogger.log("[UDP] Gagal membuka soket UDP: " + e.getMessage());
            return;
        }
        final DatagramSocket sock = udp;

        Thread rx = new Thread(new Runnable() {            // penerima UDP (TICK, ACK)
            @Override public void run() {
                byte[] buf = new byte[512];
                while (connected && !sock.isClosed()) {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    try {
                        sock.receive(p);
                        String m = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8).trim();
                        if (m.startsWith("TICK ")) {
                            int sec = parseIntSafe(m.substring(5));
                            if (sec % 5 == 0 || sec <= 5) System.out.println("   [TIMER UDP] sisa " + sec + " detik");
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

        String reg = udpRequest("REG " + myUser, "REG_");
        if ("REG_OK".equals(reg)) {
            System.out.println("[UDP] Terdaftar. Heartbeat & timer aktif.");
        } else {
            System.out.println("[UDP] Registrasi UDP gagal (" + reg + "). Fitur buzzer/timer tidak aktif.");
            return;
        }

        Thread hb = new Thread(new Runnable() {            // heartbeat berkala
            @Override public void run() {
                while (connected && !sock.isClosed()) {
                    try {
                        byte[] d = ("HB " + myUser).getBytes(StandardCharsets.UTF_8);
                        sock.send(new DatagramPacket(d, d.length, serverAddr, udpPort));
                        Thread.sleep(5000);
                    } catch (IOException e) {
                        break;
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }
        }, "udp-heartbeat");
        hb.setDaemon(true);
        hb.start();
    }

    /** Kirim paket UDP dan tunggu balasan berawalan prefix; retransmisi sampai 3x (soket timeout 1 detik). */
    private static String udpRequest(String msg, String prefix) {
        if (udp == null || udp.isClosed()) return null;
        byte[] data = msg.getBytes(StandardCharsets.UTF_8);
        for (int attempt = 1; attempt <= 3; attempt++) {
            udpReplies.clear();
            try {
                udp.send(new DatagramPacket(data, data.length, serverAddr, udpPort));
                long deadline = System.currentTimeMillis() + 1000;
                while (System.currentTimeMillis() < deadline) {
                    String r = udpReplies.poll(deadline - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
                    if (r == null) break;
                    if (r.startsWith(prefix) || r.startsWith("ERR")) return r;
                }
                ClientLogger.log("[UDP] Tidak ada balasan (percobaan " + attempt + "/3), retransmisi...");
            } catch (IOException e) {
                return "ERR " + e.getMessage();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    private static void doBuzz(Scanner scanner) {
        if (myUser == null || udp == null) { System.out.println("[INFO] Login dulu."); return; }
        String r = udpRequest("BUZZ " + myUser, "BUZZ_");
        if (r == null) { System.out.println("[UDP] Server tidak membalas BUZZ."); return; }
        switch (r) {
            case "BUZZ_WIN":
                System.out.println("*** ANDA PALING CEPAT! Jawab sekarang ***");
                askAnswer(scanner);
                break;
            case "BUZZ_LATE":    System.out.println("Terlambat: pemain lain menekan buzzer lebih dulu."); break;
            case "BUZZ_LOCKED":  System.out.println("Anda sudah salah di ronde ini, tidak bisa buzz lagi."); break;
            case "BUZZ_NOROUND": System.out.println("Tidak ada ronde aktif. Masuk lobby (menu 5), lalu mulai (menu 14)."); break;
            case "BUZZ_NOLOBBY": System.out.println("Anda tidak berada di lobby ronde ini."); break;
            default:             System.out.println("[UDP] Balasan: " + r);
        }
    }

    private static int parseIntSafe(String s) {
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return 0; }
    }

    // ------------------------------------------------------ unduh file (TCP)

    private static void doDownloadCertificate() {
        String r = sendAndShow("CERT");
        if (r == null || !r.startsWith("200 FILE ")) return;
        String[] p = r.split("\\s+");
        if (p.length < 5) { System.out.println("[ERROR] Balasan CERT tidak valid."); return; }
        int dataPort = parseIntSafe(p[2]);

        try (Socket data = new Socket()) {
            data.connect(new InetSocketAddress(serverAddr, dataPort), 5000);
            data.setSoTimeout(10_000);
            DataInputStream in = new DataInputStream(new BufferedInputStream(data.getInputStream(), 8192));

            String name = in.readUTF();                     // header: nama + ukuran
            long size = in.readLong();
            if (size < 0 || size > 50L * 1024 * 1024) throw new IOException("Ukuran file tidak wajar: " + size);
            String safeName = Paths.get(name).getFileName().toString();   // cegah path traversal
            Path dir = Paths.get("downloads");
            Files.createDirectories(dir);
            Path target = dir.resolve(safeName);

            byte[] buf = new byte[4096];                    // chunking 4KB
            long received = 0;
            try (OutputStream fos = new BufferedOutputStream(Files.newOutputStream(target))) {
                while (received < size) {
                    int n = in.read(buf, 0, (int) Math.min(buf.length, size - received));
                    if (n == -1) throw new EOFException("Koneksi putus di " + received + "/" + size + " byte");
                    fos.write(buf, 0, n);
                    received += n;
                }
            }
            boolean eof = in.read() == -1;                  // sinyal shutdownOutput() dari server
            DataOutputStream out = new DataOutputStream(data.getOutputStream());
            out.writeUTF("OK " + received);                 // verifikasi ukuran
            out.flush();
            System.out.println("[FILE] Tersimpan: " + target.toAbsolutePath() + " (" + received + "/" + size
                    + " byte" + (eof ? ", EOF diterima" : "") + ")");
        } catch (IOException e) {
            System.out.println("[FILE] Unduhan gagal: " + e.getMessage());
            ClientLogger.log("[FILE] Unduhan gagal: " + e);
        }
    }
}
