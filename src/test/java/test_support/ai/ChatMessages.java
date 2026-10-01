package test_support.ai;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

/**
 * Renders chat messages as {@code "TYPE: text"} strings, so a test can assert a whole message list
 * in one readable {@code containsExactly(user(...), assistant(...))}.
 */
public final class ChatMessages {

    private ChatMessages() {
    }

    public static String system(String text) {
        return describe(MessageType.SYSTEM, text);
    }

    public static String user(String text) {
        return describe(MessageType.USER, text);
    }

    public static String assistant(String text) {
        return describe(MessageType.ASSISTANT, text);
    }

    public static List<String> describe(List<Message> messages) {
        return messages.stream()
                .map(message -> describe(message.getMessageType(), message.getText()))
                .toList();
    }

    public static List<String> describe(Prompt prompt) {
        return describe(prompt.getInstructions());
    }

    private static String describe(MessageType type, String text) {
        return type + ": " + text;
    }
}
