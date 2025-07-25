package io.jmix.ai.backend.checks;

import io.jmix.ai.backend.entity.CheckDef;
import io.jmix.core.*;
import io.jmix.core.security.Authenticated;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;

@Component
public class DefaultChecksInitializer {

    private static final Logger log = LoggerFactory.getLogger(DefaultChecksInitializer.class);

    private final UnconstrainedDataManager dataManager;
    private final EntityImportExport entityImportExport;
    private final Resources resources;
    private final EntityImportPlans entityImportPlans;

    public DefaultChecksInitializer(UnconstrainedDataManager dataManager,
                                    EntityImportExport entityImportExport,
                                    Resources resources,
                                    EntityImportPlans entityImportPlans) {
        this.dataManager = dataManager;
        this.entityImportExport = entityImportExport;
        this.resources = resources;
        this.entityImportPlans = entityImportPlans;
    }

    @EventListener
    @Authenticated
    public void initDefaultChecks(ApplicationStartedEvent applicationStartedEvent) {
        List<CheckDef> existingCheckDefs = dataManager.load(CheckDef.class).all().maxResults(1).list();
        if (!existingCheckDefs.isEmpty()) {
            log.info("Some checks exist, skip initialization");
            return;
        }
        log.info("Initializing default checks");

        String json = resources.getResourceAsString("io/jmix/ai/backend/init/check-defs.json");
        if (json == null) {
            log.error("Failed to load default checks from resources");
            return;
        }

        Collection<Object> imported = entityImportExport.importEntitiesFromJson(
                json, entityImportPlans.builder(CheckDef.class).addLocalProperties().build());
        log.info("Imported {} default checks", imported.size());
    }
}
