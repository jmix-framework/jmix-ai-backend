package io.jmix.ai.backend.view.chatlog;

import com.vaadin.flow.data.renderer.Renderer;
import com.vaadin.flow.data.renderer.TextRenderer;
import com.vaadin.flow.router.Route;
import io.jmix.ai.backend.entity.ChatLog;
import io.jmix.ai.backend.view.main.MainView;
import io.jmix.flowui.component.filter.FilterComponent;
import io.jmix.flowui.component.genericfilter.Configuration;
import io.jmix.flowui.component.genericfilter.GenericFilter;
import io.jmix.flowui.component.grid.DataGrid;
import io.jmix.flowui.component.logicalfilter.LogicalFilterComponent;
import io.jmix.flowui.component.propertyfilter.PropertyFilter;
import io.jmix.flowui.kit.action.ActionPerformedEvent;
import io.jmix.flowui.view.*;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


@Route(value = "chat-logs", layout = MainView.class)
@ViewController(id = "ChatLog.list")
@ViewDescriptor(path = "chat-log-list-view.xml")
@LookupComponent("chatLogsDataGrid")
@DialogMode(width = "64em")
public class ChatLogListView extends StandardListView<ChatLog> {

    private static final Logger log = LoggerFactory.getLogger(ChatLogListView.class);
    @ViewComponent
    private GenericFilter filter;
    @ViewComponent
    private DataGrid<ChatLog> chatLogsDataGrid;

    @Supply(to = "chatLogsDataGrid.content", subject = "renderer")
    private Renderer<ChatLog> chatLogsDataGridContentRenderer() {
        return new TextRenderer<>(chatlog -> StringUtils.abbreviate(chatlog.getContent(), 100));
    }

    @Supply(to = "chatLogsDataGrid.sources", subject = "renderer")
    private Renderer<ChatLog> chatLogsDataGridSourcesRenderer() {
        return new TextRenderer<>(chatlog -> StringUtils.abbreviate(chatlog.getSources(), 100));
    }

    @Subscribe("chatLogsDataGrid.filterByConversationAction")
    public void onChatLogsDataGridFilterByConversationAction(final ActionPerformedEvent event) {
        ChatLog chatLog = chatLogsDataGrid.getSingleSelectedItem();
        if (chatLog == null)
            return;

        Configuration configuration = filter.getConfiguration("configurationByConversationId");
        if (configuration == null) {
            log.warn("No 'configurationByConversationId' filter configuration found");
            return;
        }
        filter.setCurrentConfiguration(configuration);

        LogicalFilterComponent<?> rootFilterComponent = configuration.getRootLogicalFilterComponent();
        for (FilterComponent filterComponent : rootFilterComponent.getFilterComponents()) {
            if (filterComponent instanceof PropertyFilter propertyFilter) {
                if ("conversationId".equals(propertyFilter.getProperty()))
                    propertyFilter.setValue(chatLog.getConversationId());
            }
        }
        filter.apply();
    }
}