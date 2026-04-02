package io.jmix.ai.backend.view.knowledgesource;

import com.vaadin.flow.router.Route;
import io.jmix.ai.backend.entity.KnowledgeSource;
import io.jmix.ai.backend.view.main.MainView;
import io.jmix.flowui.view.EditedEntityContainer;
import io.jmix.flowui.view.StandardDetailView;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;

@Route(value = "knowledge-sources/:id", layout = MainView.class)
@ViewController(id = "KnowledgeSource.detail")
@ViewDescriptor(path = "knowledge-source-detail-view.xml")
@EditedEntityContainer("knowledgeSourceDc")
public class KnowledgeSourceDetailView extends StandardDetailView<KnowledgeSource> {
}
