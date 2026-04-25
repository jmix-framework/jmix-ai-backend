package io.jmix.ai.backend.view.main;

import com.vaadin.flow.router.Route;
import io.jmix.ai.backend.checks.CheckRunCompletedUiEvent;
import io.jmix.ai.backend.entity.CheckRunStatus;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.app.main.StandardMainView;
import io.jmix.flowui.view.MessageBundle;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;

import java.util.Locale;

@Route("")
@ViewController(id = "MainView")
@ViewDescriptor(path = "main-view.xml")
public class MainView extends StandardMainView {

    @Autowired
    private Notifications notifications;
    @Autowired
    private MessageBundle messageBundle;

    @EventListener
    public void onCheckRunCompleted(CheckRunCompletedUiEvent event) {
        notifications.create(buildNotificationMessage(event))
                .withPosition(com.vaadin.flow.component.notification.Notification.Position.TOP_END)
                .withDuration(0)
                .withType(event.getStatus() == CheckRunStatus.SUCCESS ? Notifications.Type.SUCCESS : Notifications.Type.ERROR)
                .withCloseable(true)
                .show();
    }

    private String buildNotificationMessage(CheckRunCompletedUiEvent event) {
        if (event.getStatus() == CheckRunStatus.SUCCESS) {
            String score = event.getScore() != null
                    ? String.format(Locale.US, "%.3f", event.getScore())
                    : messageBundle.getMessage("checkRunNotification.scoreUnavailable");
            return messageBundle.formatMessage("checkRunNotification.success", score);
        }
        if (event.getFailureReason() != null && !event.getFailureReason().isBlank()) {
            return messageBundle.formatMessage("checkRunNotification.failedWithReason", event.getFailureReason());
        }
        return messageBundle.getMessage("checkRunNotification.failed");
    }
}
