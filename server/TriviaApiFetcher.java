package server;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * PILAR 4 - REST API Integration & JSON Parsing.
 * Mengambil soal dari Open Trivia Database memakai HttpURLConnection (GET)
 * lalu mendeserialisasi JSON ke DTO memakai Google Gson.
 */
public final class TriviaApiFetcher {
    private TriviaApiFetcher() {}

    public static final int CATEGORY_ANY = 0;
    // Bisa dioverride untuk pengujian: java -Dtrivia.url=http://localhost:8099/api.php ...
    private static final String BASE_URL = System.getProperty("trivia.url", "https://opentdb.com/api.php");
    private static final Gson GSON = new Gson();
    private static final int TIMEOUT_MS = 5000;

    public static boolean isValidCategory(int c) {
        return c == CATEGORY_ANY || (c >= 9 && c <= 32);
    }

    public static boolean isValidDifficulty(String d) {
        return "any".equals(d) || "easy".equals(d) || "medium".equals(d) || "hard".equals(d);
    }

    /**
     * @throws IOException bila jaringan gagal, HTTP bukan 200, atau JSON/respons tidak valid.
     */
    public static List<TriviaQuestionDTO> fetchQuestions(int category, String difficulty, int amount)
            throws IOException {
        StringBuilder url = new StringBuilder(BASE_URL).append("?amount=").append(amount).append("&type=multiple");
        if (category != CATEGORY_ANY) url.append("&category=").append(category);
        if (!"any".equals(difficulty)) url.append("&difficulty=").append(difficulty);

        HttpURLConnection conn = (HttpURLConnection) java.net.URI.create(url.toString()).toURL().openConnection();
        try {
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/json");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);

            int code = conn.getResponseCode();
            ServerLogger.log("[HTTP] OpenTDB GET -> HTTP " + code);
            if (code == 429) throw new IOException("RATE_LIMIT (HTTP 429)");
            if (code != HttpURLConnection.HTTP_OK) throw new IOException("HTTP " + code);

            TriviaResponseDTO dto;
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                dto = GSON.fromJson(in, TriviaResponseDTO.class);   // <-- deserialisasi Gson
            } catch (JsonParseException e) {
                throw new IOException("JSON tidak valid: " + e.getMessage());
            }

            if (dto == null) throw new IOException("Respons kosong");
            if (dto.responseCode == 5) throw new IOException("RATE_LIMIT (response_code 5)");
            if (dto.responseCode != 0) throw new IOException("OpenTDB response_code=" + dto.responseCode);
            if (dto.results == null || dto.results.isEmpty()) throw new IOException("Daftar soal kosong");

            // OpenTDB mengirim teks dengan HTML entity (&quot; dst.) -> bersihkan.
            for (TriviaQuestionDTO q : dto.results) {
                q.question = TextUtil.decode(q.question);
                q.correctAnswer = TextUtil.decode(q.correctAnswer);
                q.category = TextUtil.decode(q.category);
                if (q.incorrectAnswers != null) {
                    for (int i = 0; i < q.incorrectAnswers.size(); i++) {
                        q.incorrectAnswers.set(i, TextUtil.decode(q.incorrectAnswers.get(i)));
                    }
                }
            }
            ServerLogger.log("[SUCCESS] Gson memuat " + dto.results.size() + " soal dari OpenTDB.");
            return dto.results;
        } finally {
            conn.disconnect();   // selalu dilepas, juga saat exception
        }
    }
}
