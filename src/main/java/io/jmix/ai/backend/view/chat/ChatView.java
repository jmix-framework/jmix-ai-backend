package io.jmix.ai.backend.view.chat;


import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.router.Route;
import io.jmix.ai.backend.view.main.MainView;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.component.textarea.JmixTextArea;
import io.jmix.flowui.kit.component.button.JmixButton;
import io.jmix.flowui.view.*;
import io.micrometer.common.util.StringUtils;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;

@Route(value = "chat", layout = MainView.class)
@ViewController(id = "ChatView")
@ViewDescriptor(path = "chat-view.xml")
public class ChatView extends StandardView {

    @Autowired
    private ChatClient.Builder chatClientBuilder;
    @Autowired
    private ChatModel chatModel;
    @Autowired
    private Notifications notifications;
    @ViewComponent
    private JmixTextArea requestField;
    @ViewComponent
    private Div responseDiv;

    private ChatClient chatClient;

    @Subscribe
    public void onInit(final InitEvent event) {
        chatClient = chatClientBuilder.build();
        responseDiv.setText("Using " + chatModel.getDefaultOptions().toString());
    }

    @Subscribe(id = "sendButton", subject = "clickListener")
    public void onSendButtonClick(final ClickEvent<JmixButton> event) {
        if (StringUtils.isBlank(requestField.getValue())) {
            notifications.show("Enter some text");
        } else {
            String text = chatClient.prompt(requestField.getValue())
                    .call()
                    .content();

            Parser parser = Parser.builder().build();
            Node document = parser.parse(text);
            HtmlRenderer renderer = HtmlRenderer.builder().build();
            String html = renderer.render(document);

            responseDiv.getElement().setProperty("innerHTML", html);
        }
    }
}