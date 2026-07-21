package io.jmix.ai.backend.checks;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jmix.ai.backend.entity.CheckDef;
import io.jmix.ai.backend.entity.JmixVersion;
import io.jmix.core.FluentLoader;
import io.jmix.core.Resources;
import io.jmix.core.UnconstrainedDataManager;
import io.jmix.data.PersistenceHints;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultChecksInitializerTest {

    private static final Pattern QUOTED_UUID = Pattern.compile(
            "'([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})'");

    @Test
    void bundledDefinitionsHaveExpectedActiveCohort() throws Exception {
        String json = new String(Objects.requireNonNull(getClass().getResourceAsStream(
                "/io/jmix/ai/backend/init/check-defs.json")).readAllBytes(), StandardCharsets.UTF_8);
        String retirementMigration = new String(Objects.requireNonNull(getClass().getResourceAsStream(
                "/io/jmix/ai/backend/liquibase/changelog/2026/07/11-040000-retire-ambiguous-checks.xml"))
                .readAllBytes(), StandardCharsets.UTF_8);

        List<DefaultChecksInitializer.DefaultCheck> definitions =
                DefaultChecksInitializer.missingDefinitions(json, Set.of(), new ObjectMapper());
        Set<UUID> inactiveDefinitionIds = definitions.stream()
                .filter(definition -> Boolean.FALSE.equals(definition.active()))
                .map(DefaultChecksInitializer.DefaultCheck::id)
                .collect(Collectors.toSet());
        List<UUID> migratedIds = QUOTED_UUID.matcher(retirementMigration).results()
                .map(result -> UUID.fromString(result.group(1)))
                .toList();
        Set<UUID> versionedIds = definitions.stream()
                .filter(definition -> definition.jmixVersion() == JmixVersion.V2)
                .map(DefaultChecksInitializer.DefaultCheck::id)
                .collect(Collectors.toSet());

        // retired in changeset 1, deliberately reactivated in changeset 2 as an adversarial check
        Set<UUID> reactivatedIds = Set.of(UUID.fromString("018c1f8e-3b80-7fa0-e283-af1a2b3c4d5e"));

        assertThat(definitions).hasSize(91);
        assertThat(definitions).filteredOn(DefaultChecksInitializer.DefaultCheck::active).hasSize(69);
        assertThat(inactiveDefinitionIds).hasSize(22);
        assertThat(versionedIds).hasSize(6);
        assertThat(definitions).filteredOn(definition -> definition.jmixVersion() == null).hasSize(85);
        assertThat(Set.copyOf(migratedIds)).isEqualTo(Stream.concat(
                inactiveDefinitionIds.stream(), reactivatedIds.stream()).collect(Collectors.toSet()));
        assertThat(definitions).extracting(DefaultChecksInitializer.DefaultCheck::id).doesNotHaveDuplicates();
        assertThat(definitions).allSatisfy(definition -> {
            assertThat(definition.category()).isNotBlank();
            assertThat(definition.question()).isNotBlank();
            assertThat(definition.answer()).isNotBlank();
        });
        assertThat(definitions.stream()
                .filter(DefaultChecksInitializer.DefaultCheck::active)
                .map(DefaultChecksInitializer.DefaultCheck::question))
                .doesNotHaveDuplicates();
    }

    @Test
    void rejectsDuplicateIdsInBundledDefinitions() {
        UUID duplicateId = UUID.randomUUID();
        String json = """
                [
                  {"id":"%s","active":true,"category":"one","question":"one","answer":"one"},
                  {"id":"%s","active":true,"category":"two","question":"two","answer":"two"}
                ]
                """.formatted(duplicateId, duplicateId);

        assertThatThrownBy(() -> DefaultChecksInitializer.missingDefinitions(
                json, Set.of(), new ObjectMapper()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate default check id")
                .hasMessageContaining(duplicateId.toString());
    }

    @Test
    void rejectsUnknownJmixVersion() {
        UUID id = UUID.randomUUID();
        String json = """
                [
                  {"id":"%s","active":true,"jmixVersion":"v9","category":"one","question":"one","answer":"one"}
                ]
                """.formatted(id);

        assertThatThrownBy(() -> DefaultChecksInitializer.missingDefinitions(
                json, Set.of(), new ObjectMapper()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown Jmix version 'v9'")
                .hasMessageContaining(id.toString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void importsOnlyMissingIdsAndTreatsSoftDeletedDefaultsAsExisting() {
        UUID deletedId = UUID.randomUUID();
        UUID missingId = UUID.randomUUID();
        String json = """
                [
                  {"id":"%s","active":"true","category":"edited","question":"do not restore","answer":"old"},
                  {"id":"%s","active":"true","jmixVersion":"v3","category":"javaapi","question":"new question","answer":"new answer"}
                ]
                """.formatted(deletedId, missingId);

        UnconstrainedDataManager dataManager = mock(UnconstrainedDataManager.class);
        Resources resources = mock(Resources.class);
        FluentLoader<CheckDef> loader = mock(FluentLoader.class);
        FluentLoader.ByQuery<CheckDef> query = mock(FluentLoader.ByQuery.class);
        when(dataManager.load(CheckDef.class)).thenReturn(loader);
        when(loader.query("select e from CheckDef e")).thenReturn(query);
        when(query.hint(PersistenceHints.SOFT_DELETION, false)).thenReturn(query);

        CheckDef deleted = new CheckDef();
        deleted.setId(deletedId);
        deleted.setDeletedDate(OffsetDateTime.now());
        when(query.list()).thenReturn(List.of(deleted));
        when(resources.getResourceAsString("io/jmix/ai/backend/init/check-defs.json")).thenReturn(json);

        AtomicReference<CheckDef> created = new AtomicReference<>();
        when(dataManager.create(CheckDef.class)).thenAnswer(invocation -> {
            CheckDef entity = new CheckDef();
            created.set(entity);
            return entity;
        });

        DefaultChecksInitializer initializer =
                new DefaultChecksInitializer(dataManager, resources, new ObjectMapper());
        initializer.initDefaultChecks(null);

        verify(query).hint(PersistenceHints.SOFT_DELETION, false);
        verify(dataManager).save(any(Object[].class));
        assertThat(created.get()).isNotNull();
        assertThat(created.get().getId()).isEqualTo(missingId);
        assertThat(created.get().getJmixVersion()).isEqualTo(JmixVersion.V3);
        assertThat(created.get().getQuestion()).isEqualTo("new question");
    }
}
