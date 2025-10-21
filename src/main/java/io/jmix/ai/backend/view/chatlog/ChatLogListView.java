package io.jmix.ai.backend.view.chatlog;

import com.vaadin.flow.data.renderer.Renderer;
import com.vaadin.flow.data.renderer.TextRenderer;
import com.vaadin.flow.router.Route;
import io.jmix.ai.backend.entity.ChatLog;
import io.jmix.ai.backend.view.main.MainView;
import io.jmix.flowui.view.*;
import org.apache.commons.lang3.StringUtils;


@Route(value = "chat-logs", layout = MainView.class)
@ViewController(id = "ChatLog.list")
@ViewDescriptor(path = "chat-log-list-view.xml")
@LookupComponent("chatLogsDataGrid")
@DialogMode(width = "64em")
public class ChatLogListView extends StandardListView<ChatLog> {

    @Supply(to = "chatLogsDataGrid.content", subject = "renderer")
    private Renderer<ChatLog> chatLogsDataGridContentRenderer() {
        return new TextRenderer<>(chatlog -> StringUtils.abbreviate(chatlog.getContent(), 200));
    }

    @Supply(to = "chatLogsDataGrid.sources", subject = "renderer")
    private Renderer<ChatLog> chatLogsDataGridSourcesRenderer() {
        return new TextRenderer<>(chatlog -> StringUtils.abbreviate(chatlog.getSources(), 200));
    }
}