package server;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Penyedia soal yang tahan gangguan:
 *  1. Cache per (kategori,kesulitan) - satu panggilan API mengisi 10 soal.
 *  2. Jeda minimal 5 detik antar panggilan API (batas rate limit OpenTDB) + 1x retry.
 *  3. Bila API gagal, jatuh ke bank soal offline sehingga server TIDAK pernah macet.
 */
public final class QuestionBank {
    private QuestionBank() {}

    private static final ConcurrentHashMap<String, Queue<TriviaQuestionDTO>> CACHE = new ConcurrentHashMap<>();
    private static final Object FETCH_LOCK = new Object();
    private static final long MIN_INTERVAL_MS = 5200;
    private static long lastFetchAt = 0;   // dilindungi FETCH_LOCK

    private static final List<TriviaQuestionDTO> OFFLINE = new ArrayList<>();
    static {
        OFFLINE.add(new TriviaQuestionDTO("Computers", "easy", "Apa kepanjangan dari TCP?",
                "Transmission Control Protocol", Arrays.asList("Transfer Control Process", "Text Control Protocol", "Terminal Connection Protocol")));
        OFFLINE.add(new TriviaQuestionDTO("Computers", "easy", "Protokol manakah yang bersifat connectionless?",
                "UDP", Arrays.asList("TCP", "FTP", "SMTP")));
        OFFLINE.add(new TriviaQuestionDTO("Computers", "medium", "Port default HTTPS adalah...",
                "443", Arrays.asList("80", "21", "8080")));
        OFFLINE.add(new TriviaQuestionDTO("Computers", "medium", "Kelas Java yang dipakai untuk membuat soket UDP adalah...",
                "DatagramSocket", Arrays.asList("ServerSocket", "URLConnection", "SocketChannel")));
        OFFLINE.add(new TriviaQuestionDTO("Geography", "easy", "Ibu kota Indonesia saat ini adalah...",
                "Jakarta", Arrays.asList("Bandung", "Surabaya", "Medan")));
        OFFLINE.add(new TriviaQuestionDTO("Science & Nature", "easy", "Planet terdekat dari Matahari adalah...",
                "Merkurius", Arrays.asList("Venus", "Mars", "Bumi")));
        OFFLINE.add(new TriviaQuestionDTO("Computers", "hard", "Struktur data thread-safe berbasis hash table di Java adalah...",
                "ConcurrentHashMap", Arrays.asList("HashMap", "TreeMap", "ArrayList")));
    }

    /** Selalu mengembalikan soal (dari cache/API, atau cadangan offline). */
    public static Question next(int category, String difficulty) {
        String key = category + ":" + difficulty;
        Queue<TriviaQuestionDTO> q = CACHE.computeIfAbsent(key, k -> new ConcurrentLinkedQueue<>());

        TriviaQuestionDTO dto = q.poll();
        if (dto == null) {
            synchronized (FETCH_LOCK) {
                dto = q.poll();                       // thread lain mungkin sudah mengisi ulang
                if (dto == null) {
                    refill(q, category, difficulty);
                    dto = q.poll();
                }
            }
        }
        if (dto != null) {
            try {
                return Question.from(dto, "API");
            } catch (IllegalArgumentException e) {
                ServerLogger.log("[WARN] Soal API tidak lengkap, memakai soal offline.");
            }
        }
        return offlineQuestion(category, difficulty);
    }

    // dipanggil di dalam FETCH_LOCK
    private static void refill(Queue<TriviaQuestionDTO> q, int category, String difficulty) {
        for (int attempt = 1; attempt <= 2; attempt++) {
            long wait = MIN_INTERVAL_MS - (System.currentTimeMillis() - lastFetchAt);
            if (wait > 0 && lastFetchAt != 0) {
                try { Thread.sleep(wait); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            lastFetchAt = System.currentTimeMillis();
            try {
                q.addAll(TriviaApiFetcher.fetchQuestions(category, difficulty, 10));
                return;
            } catch (IOException e) {
                ServerLogger.log("[WARN] Gagal mengambil soal dari API (percobaan " + attempt + "): " + e.getMessage());
                if (e.getMessage() == null || !e.getMessage().startsWith("RATE_LIMIT")) return; // retry hanya utk rate limit
            }
        }
    }

    private static Question offlineQuestion(int category, String difficulty) {
        List<TriviaQuestionDTO> pool = new ArrayList<>();
        for (TriviaQuestionDTO d : OFFLINE) {
            if ("any".equals(difficulty) || d.difficulty.equals(difficulty)) pool.add(d);
        }
        if (pool.isEmpty()) pool = OFFLINE;
        TriviaQuestionDTO pick = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
        return Question.from(pick, "OFFLINE");
    }
}
