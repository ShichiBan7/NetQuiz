package server;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Soal kuis siap pakai (immutable): pilihan sudah diacak, indeks jawaban benar sudah diketahui. */
public final class Question {
    public final String text;
    public final String category;
    public final String difficulty;
    public final String source;            // "API" atau "OFFLINE"
    public final List<String> options;
    public final int correctIndex;

    private Question(String text, String category, String difficulty, String source,
                     List<String> options, int correctIndex) {
        this.text = text;
        this.category = category;
        this.difficulty = difficulty;
        this.source = source;
        this.options = Collections.unmodifiableList(options);
        this.correctIndex = correctIndex;
    }

    /** Membangun Question dari DTO. Melempar IllegalArgumentException bila data tidak lengkap. */
    public static Question from(TriviaQuestionDTO dto, String source) {
        if (dto == null || dto.question == null || dto.question.trim().isEmpty()
                || dto.correctAnswer == null || dto.incorrectAnswers == null
                || dto.incorrectAnswers.isEmpty()) {
            throw new IllegalArgumentException("Data soal tidak lengkap");
        }
        List<String> opts = new ArrayList<>();
        opts.add(dto.correctAnswer);
        opts.addAll(dto.incorrectAnswers);
        if (opts.size() > 4) opts = new ArrayList<>(opts.subList(0, 4));
        Collections.shuffle(opts);
        int idx = opts.indexOf(dto.correctAnswer);
        return new Question(dto.question, dto.category, dto.difficulty, source, opts, idx);
    }

    public String correctText() {
        return options.get(correctIndex);
    }

    /** Konversi huruf A-D ke indeks; -1 bila tidak valid. */
    public int letterToIndex(String letter) {
        if (letter == null || letter.length() != 1) return -1;
        int i = Character.toUpperCase(letter.charAt(0)) - 'A';
        return (i >= 0 && i < options.size()) ? i : -1;
    }

    /** Format satu baris: "teks || A. x || B. y ..." (pemisah "||" dipecah oleh klien). */
    public String format() {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(category == null ? "-" : category).append(" / ")
          .append(difficulty == null ? "-" : difficulty).append("] ").append(text);
        char label = 'A';
        for (String o : options) {
            sb.append(" || ").append(label++).append(". ").append(o);
        }
        return sb.toString();
    }
}
