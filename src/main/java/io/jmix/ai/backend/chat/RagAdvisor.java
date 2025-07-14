package io.jmix.ai.backend.chat;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.lang.Nullable;
import reactor.core.scheduler.Scheduler;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Based on {@code org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor}
 */
public class RagAdvisor implements BaseAdvisor {

    public static final String RETRIEVED_DOCUMENTS = "qa_retrieved_documents";

    private static final PromptTemplate DEFAULT_PROMPT_TEMPLATE = new PromptTemplate("""
			{query}

			Context information is below, surrounded by ---------------------

			---------------------
			{question_answer_context}
			---------------------

			Given the context and provided history information and not prior knowledge,
			reply to the user comment. If the answer is not in the context, inform
			the user that you can't answer the question.
			""");

    private static final int DEFAULT_ORDER = 0;

    private final List<Document> documents;
    private final PromptTemplate promptTemplate;

    private final int order;

    public RagAdvisor(List<Document> documents) {
        this(documents, DEFAULT_PROMPT_TEMPLATE, BaseAdvisor.DEFAULT_SCHEDULER, DEFAULT_ORDER);
    }

    RagAdvisor(List<Document> documents, @Nullable PromptTemplate promptTemplate,
                          @Nullable Scheduler scheduler, int order) {
        this.documents = documents;
        this.promptTemplate = promptTemplate != null ? promptTemplate : DEFAULT_PROMPT_TEMPLATE;
        this.order = order;
    }

    @Override
    public int getOrder() {
        return this.order;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest chatClientRequest, AdvisorChain advisorChain) {
        // Create the context from the documents.
        Map<String, Object> context = new HashMap<>(chatClientRequest.context());
        context.put(RETRIEVED_DOCUMENTS, documents);

        String documentContext = documents == null ? ""
                : documents.stream().map(Document::getText).collect(Collectors.joining(System.lineSeparator()));

        // Augment the user prompt with the document context.
        UserMessage userMessage = chatClientRequest.prompt().getUserMessage();
        String augmentedUserText = this.promptTemplate
                .render(Map.of("query", userMessage.getText(), "question_answer_context", documentContext));

        // Update ChatClientRequest with augmented prompt.
        return chatClientRequest.mutate()
                .prompt(chatClientRequest.prompt().augmentUserMessage(augmentedUserText))
                .context(context)
                .build();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse chatClientResponse, AdvisorChain advisorChain) {
        ChatResponse.Builder chatResponseBuilder;
        if (chatClientResponse.chatResponse() == null) {
            chatResponseBuilder = ChatResponse.builder();
        }
        else {
            chatResponseBuilder = ChatResponse.builder().from(chatClientResponse.chatResponse());
        }
        chatResponseBuilder.metadata(RETRIEVED_DOCUMENTS, chatClientResponse.context().get(RETRIEVED_DOCUMENTS));
        return ChatClientResponse.builder()
                .chatResponse(chatResponseBuilder.build())
                .context(chatClientResponse.context())
                .build();
    }
}
