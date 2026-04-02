package io.jmix.ai.backend.view.ingestionjob;

import com.vaadin.flow.router.Route;
import io.jmix.ai.backend.entity.IngestionJob;
import io.jmix.ai.backend.view.main.MainView;
import io.jmix.flowui.view.DialogMode;
import io.jmix.flowui.view.LookupComponent;
import io.jmix.flowui.view.StandardListView;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;

@Route(value = "ingestion-jobs", layout = MainView.class)
@ViewController(id = "IngestionJob.list")
@ViewDescriptor(path = "ingestion-job-list-view.xml")
@LookupComponent("ingestionJobsDataGrid")
@DialogMode(width = "72em")
public class IngestionJobListView extends StandardListView<IngestionJob> {
}
