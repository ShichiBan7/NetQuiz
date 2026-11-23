package server;

import com.google.gson.annotations.SerializedName;
import java.util.List;

/** DTO pembungkus respons JSON Open Trivia Database. */
public class TriviaResponseDTO {
    @SerializedName("response_code")
    public int responseCode;

    @SerializedName("results")
    public List<TriviaQuestionDTO> results;
}
