package io.jmix.ai.backend.view.chat;

import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.messages.MessageInput;
import com.vaadin.flow.component.messages.MessageList;
import com.vaadin.flow.component.messages.MessageListItem;
import com.vaadin.flow.router.QueryParameters;
import com.vaadin.flow.router.Route;
import io.jmix.ai.backend.chat.Chat;
import io.jmix.ai.backend.chat.StreamEvent;
import io.jmix.ai.backend.entity.Parameters;
import io.jmix.ai.backend.entity.ParametersTargetType;
import io.jmix.ai.backend.parameters.ParametersRepository;
import io.jmix.ai.backend.view.main.MainView;
import io.jmix.core.UuidProvider;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.component.valuepicker.EntityPicker;
import io.jmix.flowui.facet.UrlQueryParametersFacet;
import io.jmix.flowui.facet.urlqueryparameters.AbstractUrlQueryParametersBinder;
import io.jmix.flowui.kit.component.button.JmixButton;
import io.jmix.flowui.view.*;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.core.Disposable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;


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

    @ViewComponent
    private EntityPicker<Parameters> parametersPicker;
    @ViewComponent
    private UrlQueryParametersFacet urlQueryParameters;

    private MessageList messageList;
    private MessageInput messageInput;
    private final List<MessageListItem> items = new ArrayList<>();
    private String conversationId;
    private Disposable activeStreamDisposable;

    @Subscribe
    public void onInit(final InitEvent event) {
        parametersPicker.setValue(parametersRepository.loadActive(ParametersTargetType.CHAT));
        urlQueryParameters.registerBinder(new UrlBinder());
        updateConversationId();

        messageList = new MessageList();
        messageList.setSizeFull();
        messageList.setMarkdown(true);

        messageInput = new MessageInput();
        messageInput.setWidthFull();
        messageInput.addSubmitListener(this::onSubmit);

        var content = getContent();
        content.setSizeFull();
        // XML has hbox (toolbar) at index 0. Insert messageList after it, messageInput at the end.
        content.addComponentAtIndex(1, messageList);
        content.add(messageInput);
        content.expand(messageList);
    }

    private void onSubmit(MessageInput.SubmitEvent submitEvent) {
        String text = submitEvent.getValue();
        Parameters parameters = parametersRepository.findById(parametersPicker.getValue().getId())
                .orElse(parametersRepository.loadActive(ParametersTargetType.CHAT));

        addUserMessage(text);
        MessageListItem botMsg = addBotMessage();
        messageInput.setEnabled(false);

        UI ui = submitEvent.getSource().getUI().orElseThrow();

        // Reactive stream from ChatImpl delivers events in order:
        //   ToolCall → Content tokens → Metadata (sources)
        //
        // doOnNext     — append each markdown chunk to the bot message and scroll down
        // doOnError    — show error notification and re-enable input
        // doOnComplete — append total elapsed time, re-enable input
        // subscribe()  — starts the stream (nothing happens until subscribe is called)
        long startTime = System.currentTimeMillis();
        disposeActiveStream();
        activeStreamDisposable = chat.requestStream(text, parameters.getContent(), conversationId, null)
                .map(this::renderStreamEvent)
                .doOnNext(md -> ui.access(() -> {
                    botMsg.appendText(md);
                    scrollToBottom();
                }))
                .doOnError(e -> ui.access(() -> {
                    notifications.show("Error: " + e.getMessage());
                    messageInput.setEnabled(true);
                }))
                .doOnComplete(() -> ui.access(() -> {
                    long elapsed = System.currentTimeMillis() - startTime;
                    botMsg.appendText("\n\n---\n_Total time: %.1fs_".formatted(elapsed / 1000.0));
                    scrollToBottom();
                    messageInput.setEnabled(true);
                }))
                .subscribe();
    }

    private String renderStreamEvent(StreamEvent event) {
        return switch (event) {
            case StreamEvent.ToolCall tc -> "_Using %s %s (%.1fs)_\n\n".formatted(tc.tool(), tc.query(), tc.durationMs() / 1000.0);
            case StreamEvent.TokensStart ignored -> "";
            case StreamEvent.Content c -> c.text();
            case StreamEvent.TokensEnd ignored -> "";
            case StreamEvent.SourcesStart ignored -> "\n\n---\n**Sources:**";
            case StreamEvent.Metadata m -> "\n- [%s](%s)".formatted(m.source(), m.source());
        };
    }

    private void addUserMessage(String text) {
        var msg = new MessageListItem(text, Instant.now(), "You");
        msg.setUserColorIndex(0);
        items.add(msg);
        messageList.setItems(items);
    }

    private MessageListItem addBotMessage() {
        var msg = new MessageListItem("", Instant.now(), "AI Assistant");
        msg.setUserColorIndex(2);
        items.add(msg);
        messageList.setItems(items);
        return msg;
    }

    private void scrollToBottom() {
        messageList.getElement().executeJs("this.scrollTop = this.scrollHeight");
    }

    private void updateConversationId() {
        conversationId = UuidProvider.createUuidV7().toString();
    }

    @Subscribe
    public void onDetach(final DetachEvent event) {
        disposeActiveStream();
    }

    private void disposeActiveStream() {
        if (activeStreamDisposable != null && !activeStreamDisposable.isDisposed()) {
            activeStreamDisposable.dispose();
        }
    }

    @Subscribe(id = "newChatButton", subject = "clickListener")
    public void onNewChatButtonClick(final ClickEvent<JmixButton> event) {
        disposeActiveStream();
        items.clear();
        messageList.setItems(items);
        updateConversationId();
    }

    private class UrlBinder extends AbstractUrlQueryParametersBinder {
        public UrlBinder() {
        }

        @Override
        public void updateState(QueryParameters queryParameters) {
        }

        @Override
        public Component getComponent() {
            return null;
        }
    }
}
