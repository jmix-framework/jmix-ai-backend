package io.jmix.ai.backend.view.knowledgebase;

import com.vaadin.flow.router.Route;
import io.jmix.ai.backend.entity.KnowledgeBase;
import io.jmix.ai.backend.view.main.MainView;
import io.jmix.flowui.view.DialogMode;
import io.jmix.flowui.view.LookupComponent;
import io.jmix.flowui.view.StandardListView;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;

@Route(value = "knowledge-bases", layout = MainView.class)
@ViewController(id = "KnowledgeBase.list")
@ViewDescriptor(path = "knowledge-base-list-view.xml")
@LookupComponent("knowledgeBasesDataGrid")
@DialogMode(width = "64em")
public class KnowledgeBaseListView extends StandardListView<KnowledgeBase> {
}
