package io.jmix.ai.backend.view.chat;


import com.vaadin.flow.router.Route;
import io.jmix.ai.backend.view.main.MainView;
import io.jmix.flowui.component.textarea.JmixTextArea;
import io.jmix.flowui.view.*;
import org.apache.commons.lang3.StringUtils;

import java.util.List;

@Route(value = "chat-progress-view", layout = MainView.class)
@ViewController(id = "ChatProgressView")
@ViewDescriptor(path = "chat-progress-view.xml")
@DialogMode(width = "80%", height = "80%", resizable = true)
public class ChatProgressView extends StandardView {

    @ViewComponent
    private JmixTextArea logField;

    public void addLogMessages(List<String> changes) {
        String value = logField.getValue();
        if (!StringUtils.isEmpty(value))
            value += "\n";
        logField.setValue(value + String.join("\n", changes));
    }
}