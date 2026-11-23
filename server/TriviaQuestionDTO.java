package server;

import com.google.gson.annotations.SerializedName;
import java.util.List;

/**
 * DTO untuk satu soal dari Open Trivia Database.
 * Dideserialisasi otomatis oleh Gson (Pilar 4).
 */
public class TriviaQuestionDTO {
    @SerializedName("category")
    public String category;

    @SerializedName("type")
    public String type;

    @SerializedName("difficulty")
    public String difficulty;

    @SerializedName("question")
    public String question;

    @SerializedName("correct_answer")
    public String correctAnswer;

    @SerializedName("incorrect_answers")
    public List<String> incorrectAnswers;

    public TriviaQuestionDTO() {}

    /** Konstruktor untuk soal cadangan (offline). */
    public TriviaQuestionDTO(String category, String difficulty, String question,
                             String correctAnswer, List<String> incorrectAnswers) {
        this.category = category;
        this.type = "multiple";
        this.difficulty = difficulty;
        this.question = question;
        this.correctAnswer = correctAnswer;
        this.incorrectAnswers = incorrectAnswers;
    }
}
