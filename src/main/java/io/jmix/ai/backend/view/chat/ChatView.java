package io.jmix.ai.backend.view.chat;


import com.google.common.base.Strings;
import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.router.QueryParameters;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.VaadinServletRequest;
import io.jmix.ai.backend.chat.Chat;
import io.jmix.ai.backend.chat.ParametersRepository;
import io.jmix.ai.backend.entity.Parameters;
import io.jmix.ai.backend.view.main.MainView;
import io.jmix.flowui.Dialogs;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.backgroundtask.BackgroundTask;
import io.jmix.flowui.backgroundtask.TaskLifeCycle;
import io.jmix.flowui.component.UiComponentUtils;
import io.jmix.flowui.component.checkbox.JmixCheckbox;
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

@Route(value = "chat", layout = MainView.class)
@ViewController(id = "ChatView")
@ViewDescriptor(path = "chat-view.xml")
public class ChatView extends StandardView {

    @Autowired
    private Chat chat;
    @Autowired
    private Notifications notifications;
    @Autowired
    private Dialogs dialogs;
    @Autowired
    private ParametersRepository parametersRepository;
    @Autowired
    private DefaultUiExceptionHandler defaultUiExceptionHandler;

    @ViewComponent
    private JmixTextArea userMessageField;
    @ViewComponent
    private Div responseDiv;
    @ViewComponent
    private Div modelOptionsDiv;
    @ViewComponent
    private JmixButton clearButton;
    @ViewComponent
    private JmixButton copyButton;
    @ViewComponent
    private EntityPicker<Parameters> parametersPicker;
    @ViewComponent
    private UrlQueryParametersFacet urlQueryParameters;

    private String lastResultText;

    private String contextPath;

    @Subscribe
    public void onInit(final InitEvent event) {
        modelOptionsDiv.setText("Using " + chat.getModelOptions().toString());
        parametersPicker.setValue(parametersRepository.loadActive());

        urlQueryParameters.registerBinder(new UrlBinder());

        contextPath = VaadinServletRequest.getCurrent().getContextPath();
    }

    @Subscribe(id = "sendButton", subject = "clickListener")
    public void onSendButtonClick(final ClickEvent<JmixButton> event) {
        if (StringUtils.isBlank(userMessageField.getValue())) {
            notifications.show("Enter a question");
        } else {
            Parameters parameters = parametersRepository.findById(parametersPicker.getValue().getId()).orElseThrow();
            dialogs.createBackgroundTaskDialog(
                            new BackgroundTask<Integer, Chat.StructuredResponse>(600, this) {
                                @Override
                                public Chat.StructuredResponse run(TaskLifeCycle<Integer> taskLifeCycle) {
                                    Chat.StructuredResponse response = chat.requestStructured(userMessageField.getValue(), parameters);
                                    return response;
                                }
                                @Override
                                public void done(Chat.StructuredResponse result) {
                                    showResult(result);
                                }
                                @Override
                                public boolean handleException(Exception ex) {
                                    return defaultUiExceptionHandler.handle(ex);
                                }
                            }
                    )
                    .withHeader("Please wait")
                    .withText("Processing request...")
                    .open();
        }
    }

    private void showResult(Chat.StructuredResponse result) {
        lastResultText = result.text();

        Parser parser = Parser.builder().build();
        Node document = parser.parse(result.text());
        HtmlRenderer renderer = HtmlRenderer.builder().build();
        String html = renderer.render(document) + addSourceLinks(result.sourceLinks()) + addRetrievedDocs(result.retrievedDocuments());

        responseDiv.getElement().setProperty("innerHTML", html);

        enableResultButtons(true);
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
        String text = StringUtils.abbreviate(Objects.toString(document.getText(), ""), 80);
        return "[" + type + "] " +
                (score == null ? "" : String.format("(%.2f)", score)) + " " +
                text.replaceAll("\n", " ").replaceAll("<", "&lt;")
                        .replaceAll(">", "&gt;").replaceAll("\"", "&quot;")
                        .replaceAll("'", "&#39;");
    }

    private void enableResultButtons(boolean enable) {
        clearButton.setEnabled(enable);
        copyButton.setEnabled(enable);
    }

    @Subscribe(id = "clearButton", subject = "clickListener")
    public void onClearButtonClick(final ClickEvent<JmixButton> event) {
        responseDiv.getElement().setProperty("innerHTML", "");
        enableResultButtons(false);
        lastResultText = null;
    }

    @Subscribe(id = "copyButton", subject = "clickListener")
    public void onCopyButtonClick(final ClickEvent<JmixButton> event) {
        UiComponentUtils.copyToClipboard(lastResultText)
                .then(jsonValue -> notifications.show("Copied!"));
    }

    @Subscribe(id = "copyToClipboardButton", subject = "clickListener")
    public void onCopyToClipboardButtonClick(final ClickEvent<JmixButton> event) {
        UiComponentUtils.copyToClipboard(userMessageField.getValue())
                .then(jsonValue -> notifications.show("Copied!"));
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
}