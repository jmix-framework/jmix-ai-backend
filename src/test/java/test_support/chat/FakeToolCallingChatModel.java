package test_support.chat;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;

import java.util.List;

public class FakeToolCallingChatModel implements ChatModel {

    public static final String MODEL = "fake-model";

    private final List<AssistantMessage.ToolCall> toolCalls;
    private final List<String> answerChunks;
    private final DefaultUsage toolRoundUsage;
    private final DefaultUsage answerRoundUsage;

    public FakeToolCallingChatModel(List<AssistantMessage.ToolCall> toolCalls,
                                    List<String> answerChunks,
                                    DefaultUsage toolRoundUsage,
                                    DefaultUsage answerRoundUsage) {
        this.toolCalls = toolCalls;
        this.answerChunks = answerChunks;
        this.toolRoundUsage = toolRoundUsage;
        this.answerRoundUsage = answerRoundUsage;
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return OpenAiChatOptions.builder()
                .model(MODEL)
                .build();
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        if (!hasToolResults(prompt)) {
            return toolCallResponse();
        }
        return response(String.join("", answerChunks), answerRoundUsage);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        if (!hasToolResults(prompt)) {
            return Flux.just(toolCallResponse());
        }
        return Flux.fromIterable(answerChunks)
                .map(chunk -> new ChatResponse(List.of(new Generation(new AssistantMessage(chunk)))))
                .concatWith(Flux.just(response("", answerRoundUsage)));
    }

    private static boolean hasToolResults(Prompt prompt) {
        List<Message> instructions = prompt.getInstructions();
        return !instructions.isEmpty() && instructions.getLast() instanceof ToolResponseMessage;
    }

    private ChatResponse toolCallResponse() {
        AssistantMessage toolCallMessage = AssistantMessage.builder()
                .content("")
                .toolCalls(toolCalls)
                .build();
        return new ChatResponse(List.of(new Generation(toolCallMessage)), metadata(toolRoundUsage));
    }

    private static ChatResponse response(String text, DefaultUsage usage) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))), metadata(usage));
    }

    private static ChatResponseMetadata metadata(DefaultUsage usage) {
        return ChatResponseMetadata.builder()
                .usage(usage)
                .build();
    }
}
