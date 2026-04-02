package io.jmix.ai.backend.view.checkrun;

import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.data.renderer.Renderer;
import com.vaadin.flow.router.Route;
import io.jmix.ai.backend.checks.CheckRunner;
import io.jmix.ai.backend.entity.Check;
import io.jmix.ai.backend.entity.CheckRun;
import io.jmix.ai.backend.entity.CheckRunStatus;
import io.jmix.ai.backend.view.main.MainView;
import io.jmix.core.Id;
import io.jmix.core.metamodel.datatype.DatatypeFormatter;
import io.jmix.flowui.Dialogs;
import io.jmix.flowui.UiComponents;
import io.jmix.flowui.ViewNavigators;
import io.jmix.flowui.backgroundtask.BackgroundTask;
import io.jmix.flowui.backgroundtask.TaskLifeCycle;
import io.jmix.flowui.component.grid.DataGrid;
import io.jmix.flowui.model.CollectionLoader;
import io.jmix.flowui.view.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Locale;


@Route(value = "check-runs", layout = MainView.class)
@ViewController(id = "CheckRun.list")
@ViewDescriptor(path = "check-run-list-view.xml")
@LookupComponent("checkRunsDataGrid")
@DialogMode(width = "64em")
public class CheckRunListView extends StandardListView<CheckRun> {

    @Autowired
    private CheckRunner checkRunner;
    @Autowired
    private ViewNavigators viewNavigators;
    @Autowired
    private DatatypeFormatter datatypeFormatter;
    @Autowired
    private UiComponents uiComponents;
    @ViewComponent
    private DataGrid<CheckRun> checkRunsDataGrid;
    @ViewComponent
    private CollectionLoader<CheckRun> checkRunsDl;
    @Autowired
    private Dialogs dialogs;

    @Install(to = "checkRunsDataGrid.createAction", subject = "afterSaveHandler")
    private void checkRunsDataGridCreateActionAfterSaveHandler(final CheckRun checkRun) {
        dialogs.createBackgroundTaskDialog(
                        new BackgroundTask<Integer, Void>(3600, this) {
                            @Override
                            public Void run(TaskLifeCycle<Integer> taskLifeCycle) throws Exception {
                                checkRunner.runChecks(Id.of(checkRun));
                                return null;
                            }

                            @Override
                            public void done(Void result) {
                                checkRunsDl.load();
                            }
                        }
                )
                .withHeader("Checks are running")
                .withText("Please wait...")
//                .withTotal(10)
//                .withShowProgressInPercentage(true)
                .withCancelAllowed(true)
                .open();
    }

    @Supply(to = "checksDataGrid.link", subject = "renderer")
    private Renderer<Check> checksDataGridLinkRenderer() {
        return new ComponentRenderer<>(check -> {
            Anchor anchor = uiComponents.create(Anchor.class);
            anchor.setHref("/checks/" + check.getId() + "?mode=readonly");
            anchor.setTarget("_blank");
            anchor.add(new Icon(VaadinIcon.EXTERNAL_LINK));
            return anchor;
        });
    }

    @Supply(to = "checkRunsDataGrid.status", subject = "renderer")
    private Renderer<CheckRun> checkRunsDataGridStatusRenderer() {
        return new ComponentRenderer<>(checkRun -> {
            Span badge = new Span(checkRun.getStatus() != null ? checkRun.getStatus().name() : "-");
            badge.getStyle()
                    .set("display", "inline-block")
                    .set("padding", "0.2rem 0.65rem")
                    .set("border-radius", "999px")
                    .set("font-weight", "700")
                    .set("font-size", "var(--lumo-font-size-s)");

            CheckRunStatus status = checkRun.getStatus();
            if (status == CheckRunStatus.SUCCESS) {
                badge.getStyle()
                        .set("background", "var(--lumo-success-color-10pct)")
                        .set("color", "var(--lumo-success-text-color)");
            } else if (status == CheckRunStatus.FAILED) {
                badge.getStyle()
                        .set("background", "var(--lumo-error-color-10pct)")
                        .set("color", "var(--lumo-error-text-color)");
            } else if (status == CheckRunStatus.RUNNING) {
                badge.getStyle()
                        .set("background", "var(--lumo-primary-color-10pct)")
                        .set("color", "var(--lumo-primary-text-color)");
            } else {
                badge.getStyle()
                        .set("background", "var(--lumo-contrast-10pct)")
                        .set("color", "var(--lumo-secondary-text-color)");
            }

            return badge;
        });
    }

    @Supply(to = "checkRunsDataGrid.goldenOnly", subject = "renderer")
    private Renderer<CheckRun> checkRunsDataGridGoldenOnlyRenderer() {
        return new ComponentRenderer<>(checkRun -> {
            boolean goldenOnly = Boolean.TRUE.equals(checkRun.getGoldenOnly());
            Span badge = new Span(goldenOnly ? "GOLD" : "FULL");
            badge.getStyle()
                    .set("display", "inline-block")
                    .set("padding", "0.2rem 0.65rem")
                    .set("border-radius", "999px")
                    .set("font-weight", "800")
                    .set("font-size", "var(--lumo-font-size-s)")
                    .set("letter-spacing", "0.04em");

            if (goldenOnly) {
                badge.getStyle()
                        .set("background", "linear-gradient(135deg, var(--lumo-warning-color-10pct), rgba(255, 214, 10, 0.28))")
                        .set("color", "var(--lumo-warning-text-color)")
                        .set("border", "1px solid rgba(255, 214, 10, 0.35)");
            } else {
                badge.getStyle()
                        .set("background", "linear-gradient(135deg, var(--lumo-primary-color-10pct), rgba(76, 110, 245, 0.18))")
                        .set("color", "var(--lumo-primary-text-color)")
                        .set("border", "1px solid rgba(76, 110, 245, 0.22)");
            }

            return badge;
        });
    }

    @Supply(to = "checkRunsDataGrid.score", subject = "renderer")
    private Renderer<CheckRun> checkRunsDataGridScoreRenderer() {
        return new ComponentRenderer<>(checkRun -> {
            if (checkRun.getScore() == null) {
                return new Span("-");
            }

            double score = checkRun.getScore();
            Span badge = new Span(String.format(Locale.US, "%.3f", score));
            badge.getStyle()
                    .set("display", "inline-block")
                    .set("min-width", "3.8rem")
                    .set("text-align", "center")
                    .set("padding", "0.18rem 0.55rem")
                    .set("border-radius", "0.55rem")
                    .set("font-weight", "800");

            if (score >= 0.85) {
                badge.getStyle()
                        .set("background", "var(--lumo-success-color-10pct)")
                        .set("color", "var(--lumo-success-text-color)");
            } else if (score >= 0.75) {
                badge.getStyle()
                        .set("background", "var(--lumo-primary-color-10pct)")
                        .set("color", "var(--lumo-primary-text-color)");
            } else if (score >= 0.60) {
                badge.getStyle()
                        .set("background", "var(--lumo-warning-color-10pct)")
                        .set("color", "var(--lumo-warning-text-color)");
            } else {
                badge.getStyle()
                        .set("background", "var(--lumo-error-color-10pct)")
                        .set("color", "var(--lumo-error-text-color)");
            }

            return badge;
        });
    }

    @Supply(to = "checkRunsDataGrid.experimentKey", subject = "renderer")
    private Renderer<CheckRun> checkRunsDataGridExperimentKeyRenderer() {
        return new ComponentRenderer<>(checkRun -> {
            String experimentKey = checkRun.getExperimentKey();
            if (experimentKey == null || experimentKey.isBlank()) {
                return new Span("-");
            }

            String compact = experimentKey
                    .replace("-local-gold-", "-gold-")
                    .replace("-local-all-", "-all-");
            if (compact.length() > 42) {
                compact = compact.substring(0, 39) + "...";
            }

            Span value = new Span(compact);
            value.getElement().setProperty("title", experimentKey);
            value.getStyle()
                    .set("font-weight", "600")
                    .set("white-space", "nowrap");
            return value;
        });
    }
}
