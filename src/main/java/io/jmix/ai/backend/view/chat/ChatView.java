package io.jmix.ai.backend.view.chat;

import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.messages.MessageInput;
import com.vaadin.flow.component.messages.MessageList;
import com.vaadin.flow.component.messages.MessageListItem;
import com.vaadin.flow.router.Route;
import io.jmix.ai.backend.chat.Chat;
import io.jmix.ai.backend.chat.EventStreamValueHolder;
import io.jmix.ai.backend.chat.StreamingEvent;
import io.jmix.ai.backend.entity.JmixVersion;
import io.jmix.ai.backend.entity.Parameters;
import io.jmix.ai.backend.entity.ParametersTargetType;
import io.jmix.ai.backend.parameters.ParametersRepository;
import io.jmix.ai.backend.view.main.MainView;
import io.jmix.core.UuidProvider;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.component.select.JmixSelect;
import io.jmix.flowui.component.valuepicker.EntityPicker;
import io.jmix.flowui.facet.SettingsFacet;
import io.jmix.flowui.facet.ViewSettingsFacet;
import io.jmix.flowui.facet.settings.ViewSettings;
import io.jmix.flowui.kit.component.button.JmixButton;
import io.jmix.flowui.view.*;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.core.Disposable;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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
    private JmixSelect<JmixVersion> jmixVersionField;
    @ViewComponent
    private ViewSettingsFacet settings;
    @ViewComponent
    private MessageBundle messageBundle;

    private MessageList messageList;
    private MessageInput messageInput;
    private final List<MessageListItem> items = new ArrayList<>();
    private String conversationId;
    private Disposable activeStreamDisposable;

    @Subscribe
    public void onInit(final InitEvent event) {
        parametersPicker.setValue(parametersRepository.loadActive(ParametersTargetType.CHAT));
        jmixVersionField.setValue(JmixVersion.V2);
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
        disposeActiveStream();
        activeStreamDisposable = chat.requestStream(text, parameters.getContent(), conversationId, jmixVersionField.getValue())
                .map(this::renderStreamEvent)
                .doOnNext(md -> ui.access(() -> {
                    botMsg.appendText(md);
                    scrollToBottom();
                }))
                .doOnError(e -> ui.access(() -> {
                    notifications.show(messageBundle.formatMessage("stream.error", e.getMessage()));
                    messageInput.setEnabled(true);
                }))
                .doOnComplete(() -> ui.access(() -> messageInput.setEnabled(true)))
                .subscribe();
    }

    private String renderStreamEvent(StreamingEvent holder) {
        String ts = formatTimestamp(holder.timestamp());
        return switch (holder.value()) {
            case null -> "";
            case EventStreamValueHolder.RequestInfo requestInfo ->
                    messageBundle.formatMessage("stream.requestInfo",
                            ts, holder.conversationId(), requestInfo.model(), requestInfo.userPrompt());
            case EventStreamValueHolder.ToolCallStart toolCall ->
                    "\n\n%s **%s**: %s".formatted(ts, toolCall.tool(), toolCall.query());
            case EventStreamValueHolder.ToolRetrieved retrieved ->
                    "\n%s ".formatted(ts) + renderDocList(
                            messageBundle.getMessage("stream.retrieved"),
                            retrieved.documents(), retrieved.durationMs());
            case EventStreamValueHolder.ToolReranked reranked ->
                    "\n%s ".formatted(ts) + renderDocList(
                            messageBundle.getMessage("stream.reranked"),
                            reranked.documents(), reranked.durationMs());
            case EventStreamValueHolder.ToolCallEnd toolCallEnd ->
                    messageBundle.formatMessage(
                            "stream.toolDone", ts, toolCallEnd.tool(), formatMs(toolCallEnd.totalDurationMs()));
            case EventStreamValueHolder.TokensStart ignored -> "";
            case EventStreamValueHolder.Content(String text) -> text;
            case EventStreamValueHolder.TokensEnd ignored -> "";
            case EventStreamValueHolder.SourcesStart ignored ->
                    messageBundle.getMessage("stream.sources");
            case EventStreamValueHolder.Metadata(String source) ->
                    "\n- [%s](%s)".formatted(source, source);
            case EventStreamValueHolder.RequestEnd requestEnd ->
                    messageBundle.formatMessage("stream.requestEnd",
                            ts, requestEnd.totalDurationMs(), requestEnd.promptTokens(),
                            requestEnd.completionTokens());
        };
    }

    private static String renderDocList(String label, List<EventStreamValueHolder.DocScore> docs, long durationMs) {
        if (docs.isEmpty()) return "  \n%s (0) - %s".formatted(label, formatMs(durationMs));
        var sb = new StringBuilder("  \n%s (%d) - %s: ".formatted(label, docs.size(), formatMs(durationMs)));
        var entries = docs.stream()
                .map(d -> "(%.3f) %s".formatted(d.score(), d.url()))
                .toList();
        sb.append(String.join(", ", entries));
        return sb.toString();
    }

    private static String formatTimestamp(Instant timestamp) {
        return LocalTime.ofInstant(timestamp, ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("HH:mm:ss"));
    }

    private static String formatMs(long ms) {
        return ms < 1000 ? ms + "ms" : "%.1fs".formatted(ms / 1000.0);
    }

    private void addUserMessage(String text) {
        var msg = new MessageListItem(text, Instant.now(), messageBundle.getMessage("message.user"));
        msg.setUserColorIndex(0);
        items.add(msg);
        messageList.setItems(items);
    }

    private MessageListItem addBotMessage() {
        var msg = new MessageListItem("", Instant.now(), messageBundle.getMessage("message.assistant"));
        msg.setUserColorIndex(2);
        items.add(msg);
        messageList.setItems(items);
        return msg;
    }

    /**
     * Auto-scrolls to bottom only if the user hasn't scrolled up.
     * Uses setTimeout to run after markdown re-renders — without it,
     * scrollHeight may not reflect the new content yet, causing
     * the threshold check to falsely detect "user scrolled up".
     */
    private void scrollToBottom() {
        messageList.getElement().executeJs(
                "setTimeout(() => { " +
                "if (this.scrollHeight - this.scrollTop - this.clientHeight < 50) " +
                "this.scrollTop = this.scrollHeight; }, 50)");
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

    @Install(to = "settings", subject = "applySettingsDelegate")
    private void settingsApplySettingsDelegate(final SettingsFacet.SettingsContext<ViewSettings> settingsContext) {
        settings.applySettings();
        settingsContext.getSettings().getString("jmixVersionField", "value").ifPresent(value ->
                jmixVersionField.setValue(JmixVersion.fromId(value)));
    }

    @Install(to = "settings", subject = "saveSettingsDelegate")
    private void settingsSaveSettingsDelegate(final SettingsFacet.SettingsContext<ViewSettings> settingsContext) {
        settingsContext.getSettings().put("jmixVersionField", "value", jmixVersionField.getValue().getId());
        settings.saveSettings();
    }
}
