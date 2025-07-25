package io.jmix.ai.backend.view.check;

import com.vaadin.flow.router.Route;
import io.jmix.ai.backend.entity.Check;
import io.jmix.ai.backend.view.main.MainView;
import io.jmix.flowui.view.*;

@Route(value = "checks/:id", layout = MainView.class)
@ViewController(id = "Check_.detail")
@ViewDescriptor(path = "check-detail-view.xml")
@EditedEntityContainer("checkDc")
@DialogMode(width = "90%", height = "90%", closeOnEsc = true, closeOnOutsideClick = true, resizable = true)
public class CheckDetailView extends StandardDetailView<Check> {

    @Override
    public boolean hasUnsavedChanges() {
        return false;
    }
}