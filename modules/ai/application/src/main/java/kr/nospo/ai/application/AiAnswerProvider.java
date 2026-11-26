package kr.nospo.ai.application;

import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

public interface AiAnswerProvider {
    record Turn(String role, String content) {}

    boolean available();
    void generate(String approvedContext, List<Turn> history, String question,
                  Duration remaining, Consumer<String> delta) throws Exception;
}
