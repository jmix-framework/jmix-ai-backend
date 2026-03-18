package io.jmix.ai.backend.view.chat;


import com.google.common.base.Strings;
import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.details.Details;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Hr;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.QueryParameters;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.VaadinServletRequest;
import io.jmix.ai.backend.chat.Chat;
import io.jmix.ai.backend.chatlog.ChatLogManager;
import io.jmix.ai.backend.entity.Parameters;
import io.jmix.ai.backend.entity.ParametersTargetType;
import io.jmix.ai.backend.parameters.ParametersRepository;
import io.jmix.ai.backend.view.main.MainView;
import io.jmix.core.UuidProvider;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.UiComponents;
import io.jmix.flowui.backgroundtask.BackgroundTask;
import io.jmix.flowui.backgroundtask.BackgroundTaskHandler;
import io.jmix.flowui.backgroundtask.BackgroundWorker;
import io.jmix.flowui.backgroundtask.TaskLifeCycle;
import io.jmix.flowui.component.UiComponentUtils;
import io.jmix.flowui.component.scroller.JmixScroller;
import io.jmix.flowui.component.textarea.JmixTextArea;
import io.jmix.flowui.component.valuepicker.EntityPicker;
import io.jmix.flowui.exception.DefaultUiExceptionHandler;
import io.jmix.flowui.facet.UrlQueryParametersFacet;
import io.jmix.flowui.facet.urlqueryparameters.AbstractUrlQueryParametersBinder;
import io.jmix.flowui.kit.component.button.JmixButton;
import io.jmix.flowui.view.*;
import org.apache.commons.lang3.StringUtils;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

@Route(value = "chat-view", layout = MainView.class)
@ViewController(id = "ChatView")
@ViewDescriptor(path = "chat-view.xml")
public class ChatView extends StandardView {

    @Autowired
    private Chat chat;
    @Autowired
    private Notifications notifications;
    @Autowired
    private ParametersRepository parametersRepository;
    @Autowired
    private DefaultUiExceptionHandler defaultUiExceptionHandler;
    @Autowired
    private BackgroundWorker backgroundWorker;
    @Autowired
    private UiComponents uiComponents;
    @Autowired
    private ChatLogManager chatLogManager;

    @ViewComponent
    private JmixTextArea userMessageField;
    @ViewComponent
    private EntityPicker<Parameters> parametersPicker;
    @ViewComponent
    private UrlQueryParametersFacet urlQueryParameters;
    @ViewComponent
    private JmixScroller scroller;
    @ViewComponent
    private VerticalLayout responseBox;
    @ViewComponent
    private MessageBundle messageBundle;

    private String contextPath;

    private String conversationId;

    @Subscribe
    public void onInit(final InitEvent event) {
        parametersPicker.setValue(parametersRepository.loadActive(ParametersTargetType.CHAT));

        urlQueryParameters.registerBinder(new UrlBinder());

        contextPath = VaadinServletRequest.getCurrent().getContextPath();

        updateConversationId();
    }

    private void updateConversationId() {
        conversationId = UuidProvider.createUuidV7().toString();
    }

    @Subscribe(id = "sendButton", subject = "clickListener")
    public void onSendButtonClick(final ClickEvent<JmixButton> event) {
        if (StringUtils.isBlank(userMessageField.getValue())) {
            notifications.show(messageBundle.getMessage("chatView.enterQuestion"));
        } else {
            String requestText = userMessageField.getValue();
            String requestPreview = StringUtils.abbreviate(requestText, 100);
            String requestConversationId = conversationId;
            Parameters parameters = parametersRepository.findById(parametersPicker.getValue().getId())
                    .orElse(parametersRepository.loadActive(ParametersTargetType.CHAT));
            StreamingResponseUi streamingResponseUi = createStreamingResponseUi(requestPreview);
            ChatBackgroundTask task = new ChatBackgroundTask(parameters, streamingResponseUi, requestText, requestConversationId);
            final BackgroundTaskHandler<Chat.StructuredResponse> taskHandler = backgroundWorker.handle(task);
            streamingResponseUi.setStopAction(() -> {
                if (taskHandler.cancel()) {
                    streamingResponseUi.markStopping();
                }
            });
            taskHandler.execute();
        }
    }

    @Subscribe(id = "newChatButton", subject = "clickListener")
    public void onNewChatButtonClick(final ClickEvent<JmixButton> event) {
        responseBox.removeAll();
        updateConversationId();
    }

    private void showResult(StreamingResponseUi streamingResponseUi, String requestConversationId, Chat.StructuredResponse result) {
        streamingResponseUi.setLogText("Conversation ID: " + requestConversationId + "\n" + String.join("\n", result.logMessages()));
        streamingResponseUi.finish(result.text(),
                addSourceLinks(result.sourceLinks()) + addRetrievedDocs(result.retrievedDocuments()));
    }

    private StreamingResponseUi createStreamingResponseUi(String requestText) {
        if (responseBox.getChildren().findAny().isPresent()) {
            responseBox.add(uiComponents.create(Hr.class));
        }

        Div requestDiv = uiComponents.create(Div.class);
        requestDiv.setText(requestText);
        requestDiv.getStyle().set("font-weight", "bold");
        responseBox.add(requestDiv);

        JmixTextArea logField = uiComponents.create(JmixTextArea.class);
        logField.setWidthFull();
        logField.setHeight("16em");
        logField.setReadOnly(true);
        logField.getStyle().set("font-family", "monospace");
        logField.getStyle().set("font-size", "smaller");
        logField.setVisible(false);

        Details progressDetails = new Details("Progress / logs", logField);
        progressDetails.setSummaryText(messageBundle.getMessage("chatView.progressLogs"));
        progressDetails.setWidthFull();
        progressDetails.setOpened(false);
        responseBox.add(progressDetails);

        Div responseDiv = uiComponents.create(Div.class);
        responseDiv.setText("");
        responseBox.add(responseDiv);

        Button copyButton = uiComponents.create(Button.class);
        copyButton.setText(messageBundle.getMessage("chatView.copy"));
        copyButton.setIcon(new Icon(VaadinIcon.CLIPBOARD));
        copyButton.setEnabled(false);

        Button stopButton = uiComponents.create(Button.class);
        stopButton.setText(messageBundle.getMessage("chatView.stop"));
        stopButton.setIcon(new Icon(VaadinIcon.STOP));
        stopButton.addThemeVariants(ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_TERTIARY);

        HorizontalLayout actionsLayout = uiComponents.create(HorizontalLayout.class);
        actionsLayout.setPadding(false);
        actionsLayout.setSpacing(true);
        actionsLayout.add(copyButton, stopButton);
        responseBox.add(actionsLayout);

        scroller.scrollToBottom();
        return new StreamingResponseUi(logField, progressDetails, responseDiv, copyButton, stopButton);
    }

    private String addSourceLinks(@Nullable List<String> strings) {
        if (strings == null || strings.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("<hr><p><strong>Source links:</strong></p><ul>");
        for (String string : strings) {
            sb.append("<li><a href=\"").append(string).append("\" target=\"_blank\">").append(string).append("</a></li>");
        }
        sb.append("</ul>");
        return sb.toString();
    }

    private String addRetrievedDocs(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("<hr><p><strong>Retrieved documents:</strong></p><ul>");
        for (Document document : documents) {
            sb.append("<li><a href=\"").append(getDocLinkUrl(document)).append("\" target=\"_blank\">").append(getDocLinkText(document)).append("</a></li>");
        }
        sb.append("</ul>");
        return sb.toString();
    }

    private String getDocLinkUrl(Document document) {
        return contextPath + "/vector-store/" + document.getId();
    }

    private String getDocLinkText(Document document) {
        Object type = document.getMetadata().get("type");
        Double score = document.getScore();
        Double rerankScore = (Double) document.getMetadata().get("rerankScore");
        String text = StringUtils.abbreviate(Objects.toString(document.getText(), ""), 80);
        return "[" + type + "] " +
                getScoreString(score, rerankScore) + " " +
                text.replaceAll("\n", " ").replaceAll("<", "&lt;")
                        .replaceAll(">", "&gt;").replaceAll("\"", "&quot;")
                        .replaceAll("'", "&#39;");
    }

    private static String getScoreString(Double score, Double rerankScore) {
        if (score == null && rerankScore == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder("(");
        if (score != null) {
            sb.append(String.format("%.2f", score));
        }
        sb.append("/");
        if (rerankScore != null) {
            sb.append(String.format("%.2f", rerankScore));
        }
        sb.append(")");
        return sb.toString();
    }

    @Subscribe(id = "copyToClipboardButton", subject = "clickListener")
    public void onCopyToClipboardButtonClick(final ClickEvent<JmixButton> event) {
        if (StringUtils.isNotBlank(userMessageField.getValue())) {
            UiComponentUtils.copyToClipboard(userMessageField.getValue())
                    .then(jsonValue -> notifications.show("Copied!"));
        }
    }

    private class UrlBinder extends AbstractUrlQueryParametersBinder {
        public UrlBinder() {
            userMessageField.addValueChangeListener(valueChangeEvent -> {
                String value = Strings.nullToEmpty(valueChangeEvent.getValue());
                QueryParameters queryParameters = QueryParameters.of("userMessage", value);
                fireQueryParametersChanged(new UrlQueryParametersFacet.UrlQueryParametersChangeEvent(this, queryParameters));
            });
        }

        @Override
        public void updateState(QueryParameters queryParameters) {
            Optional<String> userMessageOptional = queryParameters.getSingleParameter("userMessage");
            userMessageField.setValue(userMessageOptional.orElse(""));
        }

        @Override
        public Component getComponent() {
            return null;
        }
    }

    private class ChatBackgroundTask extends BackgroundTask<ChatUpdate, Chat.StructuredResponse> {

        private final Parameters parameters;
        private final StreamingResponseUi streamingResponseUi;
        private final String requestText;
        private final String requestConversationId;

        public ChatBackgroundTask(Parameters parameters, StreamingResponseUi streamingResponseUi,
                                  String requestText, String requestConversationId) {
            super(600, ChatView.this);
            this.parameters = parameters;
            this.streamingResponseUi = streamingResponseUi;
            this.requestText = requestText;
            this.requestConversationId = requestConversationId;
        }

        @Override
        public Chat.StructuredResponse run(TaskLifeCycle<ChatUpdate> taskLifeCycle) throws Exception {
            Consumer<String> logger = s -> {
                try {
                    taskLifeCycle.publish(ChatUpdate.log(s));
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            };
            Consumer<String> chunkConsumer = chunk -> {
                try {
                    taskLifeCycle.publish(ChatUpdate.chunk(chunk));
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            };
            Chat.StructuredResponse response = chat.requestStructuredStreaming(
                    requestText, parameters.getContent(), requestConversationId,
                    () -> taskLifeCycle.isCancelled() || taskLifeCycle.isInterrupted(),
                    chunkConsumer, logger);
            return response;
        }

        @Override
        public void progress(List<ChatUpdate> changes) {
            for (ChatUpdate change : changes) {
                if (change.type() == ChatUpdateType.LOG) {
                    streamingResponseUi.appendLog(change.value());
                } else if (change.type() == ChatUpdateType.CHUNK) {
                    streamingResponseUi.appendChunk(change.value());
                }
            }
        }

        @Override
        public void done(Chat.StructuredResponse result) {
            chatLogManager.saveResponse(requestConversationId, result);
            showResult(streamingResponseUi, requestConversationId, result);
        }

        @Override
        public void canceled() {
            streamingResponseUi.finishCancelled();
        }

        @Override
        public boolean handleException(Exception ex) {
            return defaultUiExceptionHandler.handle(ex);
        }
    }

    private record ChatUpdate(ChatUpdateType type, String value) {
        static ChatUpdate log(String value) {
            return new ChatUpdate(ChatUpdateType.LOG, value);
        }

        static ChatUpdate chunk(String value) {
            return new ChatUpdate(ChatUpdateType.CHUNK, value);
        }
    }

    private enum ChatUpdateType {
        LOG,
        CHUNK
    }

    private class StreamingResponseUi {

        private final JmixTextArea logField;
        private final Details progressDetails;
        private final Div responseDiv;
        private final Button copyButton;
        private final Button stopButton;
        private final StringBuilder logBuilder = new StringBuilder();
        private final StringBuilder markdownBuilder = new StringBuilder();
        private Runnable stopAction;

        private StreamingResponseUi(JmixTextArea logField, Details progressDetails, Div responseDiv, Button copyButton,
                                    Button stopButton) {
            this.logField = logField;
            this.progressDetails = progressDetails;
            this.responseDiv = responseDiv;
            this.copyButton = copyButton;
            this.stopButton = stopButton;
            if (this.copyButton != null) {
                this.copyButton.addClickListener(clickEvent -> UiComponentUtils.copyToClipboard(markdownBuilder.toString())
                        .then(jsonValue -> notifications.show(messageBundle.getMessage("chatView.copied"))));
            }
            if (this.stopButton != null) {
                this.stopButton.addClickListener(clickEvent -> {
                    if (stopAction != null) {
                        stopAction.run();
                    }
                });
            }
        }

        private void setStopAction(Runnable stopAction) {
            this.stopAction = stopAction;
        }

        private void appendLog(String logMessage) {
            if (logBuilder.length() > 0) {
                logBuilder.append("\n");
            }
            logBuilder.append(logMessage);
            if (logField != null && progressDetails != null) {
                logField.setVisible(true);
                logField.setValue(logBuilder.toString());
                progressDetails.setSummaryText(messageBundle.getMessage("chatView.progressLogs"));
            }
        }

        private void setLogText(String logText) {
            logBuilder.setLength(0);
            logBuilder.append(logText);
            if (logField != null && progressDetails != null) {
                logField.setVisible(true);
                logField.setValue(logText);
                progressDetails.setSummaryText(messageBundle.getMessage("chatView.progressLogs"));
            }
        }

        private void appendChunk(String chunk) {
            markdownBuilder.append(chunk);
            renderMarkdown(markdownBuilder.toString(), "");
        }

        private void finish(String markdown, String extraHtml) {
            markdownBuilder.setLength(0);
            markdownBuilder.append(markdown);
            renderMarkdown(markdown, extraHtml);
            if (copyButton != null) {
                copyButton.setEnabled(true);
            }
            if (stopButton != null) {
                stopButton.setEnabled(false);
            }
            if (progressDetails != null) {
                progressDetails.setOpened(false);
            }
        }

        private void markStopping() {
            if (stopButton != null) {
                stopButton.setEnabled(false);
                stopButton.setText(messageBundle.getMessage("chatView.stopping"));
            }
        }

        private void finishCancelled() {
            appendLog(messageBundle.getMessage("chatView.cancelledLog"));
            renderMarkdown(markdownBuilder.toString(), "<p><em>" + messageBundle.getMessage("chatView.cancelled") + "</em></p>");
            if (copyButton != null) {
                copyButton.setEnabled(markdownBuilder.length() > 0);
            }
            if (stopButton != null) {
                stopButton.setEnabled(false);
                stopButton.setText(messageBundle.getMessage("chatView.stopped"));
            }
            if (progressDetails != null) {
                progressDetails.setOpened(false);
            }
        }

        private void renderMarkdown(String markdown, String extraHtml) {
            if (responseDiv == null) {
                return;
            }
            Parser parser = Parser.builder().build();
            Node document = parser.parse(markdown);
            HtmlRenderer renderer = HtmlRenderer.builder().escapeHtml(true).build();
            responseDiv.getElement().setProperty("innerHTML", renderer.render(document) + extraHtml);
            scroller.scrollToBottom();
        }
    }
}
