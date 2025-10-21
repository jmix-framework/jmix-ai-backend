package io.jmix.ai.backend.view.chatlog;

import com.vaadin.flow.router.Route;
import io.jmix.ai.backend.entity.ChatLog;
import io.jmix.ai.backend.view.main.MainView;
import io.jmix.flowui.model.InstanceLoader;
import io.jmix.flowui.view.*;

@Route(value = "chat-logs/:id", layout = MainView.class)
@ViewController(id = "ChatLog.detail")
@ViewDescriptor(path = "chat-log-detail-view.xml")
@EditedEntityContainer("chatLogDc")
public class ChatLogDetailView extends StandardDetailView<ChatLog> {

    @Subscribe(id = "chatLogDl", target = Target.DATA_LOADER)
    public void onChatLogDlPostLoad(final InstanceLoader.PostLoadEvent<ChatLog> event) {
        ChatLog chatLog = event.getLoadedEntity();
        chatLog.setSources(formatSources(chatLog.getSources()));
    }

    private String formatSources(String sources) {
        return sources == null ? "" : sources.replaceAll(",", "\n");
    }
}