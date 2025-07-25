package io.jmix.ai.backend.view.checkdef;

import com.vaadin.flow.router.Route;
import io.jmix.ai.backend.entity.CheckDef;
import io.jmix.ai.backend.view.main.MainView;
import io.jmix.flowui.view.EditedEntityContainer;
import io.jmix.flowui.view.StandardDetailView;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;

@Route(value = "check-defs/:id", layout = MainView.class)
@ViewController(id = "CheckDef.detail")
@ViewDescriptor(path = "check-def-detail-view.xml")
@EditedEntityContainer("checkDefDc")
public class CheckDefDetailView extends StandardDetailView<CheckDef> {
}