package io.jmix.ai.backend.vectorstore;

import io.jmix.ai.backend.entity.IngestionJob;
import io.jmix.ai.backend.entity.IngestionJobStatus;
import io.jmix.ai.backend.entity.KnowledgeBase;
import io.jmix.ai.backend.entity.KnowledgeSource;
import io.jmix.ai.backend.entity.KnowledgeSourceType;
import io.jmix.ai.backend.entity.KnowledgeSourceUpdateMode;
import io.jmix.core.DataManager;
import io.jmix.core.SaveContext;
import io.jmix.core.TimeSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

@Component
public class KnowledgeSourceManager {

    private final DataManager dataManager;
    private final TimeSource timeSource;
    private final String docsBaseUrl;
    private final String trainingsLocalPath;
    private final String uiSamplesLocalPath;
    private final String jmixFrameworkLocalPath;

    public KnowledgeSourceManager(
            DataManager dataManager,
            TimeSource timeSource,
            @Value("${docs.base-url}") String docsBaseUrl,
            @Value("${trainings.local-path}") String trainingsLocalPath,
            @Value("${uisamples.local-path}") String uiSamplesLocalPath,
            @Value("${jmix-framework.local-path}") String jmixFrameworkLocalPath) {
        this.dataManager = dataManager;
        this.timeSource = timeSource;
        this.docsBaseUrl = docsBaseUrl;
        this.trainingsLocalPath = trainingsLocalPath;
        this.uiSamplesLocalPath = uiSamplesLocalPath;
        this.jmixFrameworkLocalPath = jmixFrameworkLocalPath;
    }

    public KnowledgeSourceContext resolve(String ingesterType) {
        DefaultKnowledgeConfig config = defaultConfig(ingesterType);

        KnowledgeBase knowledgeBase = loadKnowledgeBase(config.kbCode());
        if (knowledgeBase == null) {
            knowledgeBase = dataManager.create(KnowledgeBase.class);
            knowledgeBase.setCode(config.kbCode());
            knowledgeBase.setName(config.kbName());
            knowledgeBase.setDescription(config.kbDescription());
            knowledgeBase.setDefaultLanguage(config.language());
            knowledgeBase.setActive(true);
        } else {
            if (knowledgeBase.getActive() == null) {
                knowledgeBase.setActive(true);
            }
            if (knowledgeBase.getName() == null) {
                knowledgeBase.setName(config.kbName());
            }
            if (knowledgeBase.getDescription() == null) {
                knowledgeBase.setDescription(config.kbDescription());
            }
            if (knowledgeBase.getDefaultLanguage() == null) {
                knowledgeBase.setDefaultLanguage(config.language());
            }
        }

        KnowledgeSource knowledgeSource = loadKnowledgeSource(knowledgeBase.getCode(), config.sourceCode());
        if (knowledgeSource == null) {
            knowledgeSource = dataManager.create(KnowledgeSource.class);
            knowledgeSource.setKnowledgeBase(knowledgeBase);
            knowledgeSource.setCode(config.sourceCode());
            knowledgeSource.setName(config.sourceName());
            knowledgeSource.setSourceType(config.sourceType());
            knowledgeSource.setLocation(config.location());
            knowledgeSource.setLanguage(config.language());
            knowledgeSource.setEnabled(true);
            knowledgeSource.setUpdateMode(config.updateMode());
        } else {
            if (knowledgeSource.getEnabled() == null) {
                knowledgeSource.setEnabled(true);
            }
            if (knowledgeSource.getSourceType() == null) {
                knowledgeSource.setSourceType(config.sourceType());
            }
            if (knowledgeSource.getUpdateMode() == null) {
                knowledgeSource.setUpdateMode(config.updateMode());
            }
            if (knowledgeSource.getLocation() == null) {
                knowledgeSource.setLocation(config.location());
            }
            if (knowledgeSource.getLanguage() == null) {
                knowledgeSource.setLanguage(config.language());
            }
            if (knowledgeSource.getName() == null) {
                knowledgeSource.setName(config.sourceName());
            }
        }

        SaveContext saveContext = new SaveContext().saving(knowledgeBase).saving(knowledgeSource);
        dataManager.save(saveContext);

        return new KnowledgeSourceContext(knowledgeBase, knowledgeSource);
    }

    public boolean isEnabled(String ingesterType) {
        KnowledgeSource source = findExistingSource(ingesterType);
        if (source != null) {
            return source.getEnabled() != Boolean.FALSE;
        }
        return resolve(ingesterType).knowledgeSource().getEnabled() != Boolean.FALSE;
    }

    public KnowledgeSource findExistingSource(String ingesterType) {
        DefaultKnowledgeConfig config = defaultConfig(ingesterType);
        return loadKnowledgeSource(config.kbCode(), config.sourceCode());
    }

    public IngestionJob startJob(KnowledgeSourceContext context) {
        IngestionJob job = dataManager.create(IngestionJob.class);
        job.setKnowledgeBase(context.knowledgeBase());
        job.setKnowledgeSource(context.knowledgeSource());
        job.setStatus(IngestionJobStatus.RUNNING);
        job.setStartedAt(now());
        return dataManager.save(job);
    }

    public void completeJob(IngestionJob job, KnowledgeSourceContext context,
                            int loadedSources, int addedDocuments, int addedChunks, String message) {
        job.setStatus(IngestionJobStatus.SUCCESS);
        job.setFinishedAt(now());
        job.setLoadedSources(loadedSources);
        job.setAddedDocuments(addedDocuments);
        job.setAddedChunks(addedChunks);
        job.setMessage(message);

        KnowledgeSource knowledgeSource = context.knowledgeSource();
        knowledgeSource.setLastSuccessfulIngestionAt(now());

        dataManager.save(new SaveContext().saving(job).saving(knowledgeSource));
    }

    public void failJob(IngestionJob job, int loadedSources, String errorDetails) {
        job.setStatus(IngestionJobStatus.FAILED);
        job.setFinishedAt(now());
        job.setLoadedSources(loadedSources);
        job.setErrorDetails(trim(errorDetails));
        dataManager.save(job);
    }

    private KnowledgeBase loadKnowledgeBase(String code) {
        return dataManager.load(KnowledgeBase.class)
                .query("e.code = ?1", code)
                .optional()
                .orElse(null);
    }

    private KnowledgeSource loadKnowledgeSource(String kbCode, String sourceCode) {
        return dataManager.load(KnowledgeSource.class)
                .query("select e from KnowledgeSource e where e.knowledgeBase.code = ?1 and e.code = ?2", kbCode, sourceCode)
                .optional()
                .orElse(null);
    }

    private OffsetDateTime now() {
        return timeSource.now().toOffsetDateTime();
    }

    private String trim(String value) {
        if (value == null) {
            return null;
        }
        return value.length() > 4000 ? value.substring(0, 4000) : value;
    }

    private DefaultKnowledgeConfig defaultConfig(String ingesterType) {
        return switch (ingesterType) {
            case "docs" -> new DefaultKnowledgeConfig(
                    "jmix-docs-ru",
                    "Jmix Docs RU",
                    "Official Jmix 1.7 Russian documentation",
                    "ru",
                    "docs-site",
                    "Jmix Docs Site",
                    KnowledgeSourceType.DOCS_SITE,
                    KnowledgeSourceUpdateMode.ON_DEMAND,
                    docsBaseUrl
            );
            case "trainings" -> new DefaultKnowledgeConfig(
                    "jmix-trainings-ru",
                    "Jmix Trainings RU",
                    "Local Jmix training materials in Russian",
                    "ru",
                    "trainings-local",
                    "Local Trainings Repository",
                    KnowledgeSourceType.LOCAL_REPOSITORY,
                    KnowledgeSourceUpdateMode.MANUAL,
                    trainingsLocalPath
            );
            case "uisamples" -> new DefaultKnowledgeConfig(
                    "jmix-ui-samples",
                    "Jmix UI Samples",
                    "Classic UI samples from local repository checkout",
                    "en",
                    "uisamples-local",
                    "Local UI Samples Repository",
                    KnowledgeSourceType.LOCAL_REPOSITORY,
                    KnowledgeSourceUpdateMode.MANUAL,
                    uiSamplesLocalPath
            );
            case "jmix-framework-code" -> new DefaultKnowledgeConfig(
                    "jmix-framework-1.7",
                    "Jmix Framework 1.7",
                    "Open-source Jmix 1.7 framework code limited to core, data, ui, and security modules",
                    "en",
                    "jmix-framework-code-1.7.2",
                    "Jmix Framework Code 1.7.2",
                    KnowledgeSourceType.LOCAL_DIRECTORY,
                    KnowledgeSourceUpdateMode.MANUAL,
                    jmixFrameworkLocalPath
            );
            default -> throw new IllegalArgumentException("Unknown ingester type: " + ingesterType);
        };
    }

    private record DefaultKnowledgeConfig(
            String kbCode,
            String kbName,
            String kbDescription,
            String language,
            String sourceCode,
            String sourceName,
            KnowledgeSourceType sourceType,
            KnowledgeSourceUpdateMode updateMode,
            String location
    ) {
    }
}
