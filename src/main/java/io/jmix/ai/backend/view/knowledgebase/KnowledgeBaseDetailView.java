package io.jmix.ai.backend.view.knowledgebase;

import com.vaadin.flow.router.Route;
import io.jmix.ai.backend.entity.KnowledgeBase;
import io.jmix.ai.backend.view.main.MainView;
import io.jmix.flowui.view.EditedEntityContainer;
import io.jmix.flowui.view.StandardDetailView;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;

@Route(value = "knowledge-bases/:id", layout = MainView.class)
@ViewController(id = "KnowledgeBase.detail")
@ViewDescriptor(path = "knowledge-base-detail-view.xml")
@EditedEntityContainer("knowledgeBaseDc")
public class KnowledgeBaseDetailView extends StandardDetailView<KnowledgeBase> {
}
