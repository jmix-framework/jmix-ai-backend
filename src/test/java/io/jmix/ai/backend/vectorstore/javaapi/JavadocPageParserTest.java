package io.jmix.ai.backend.vectorstore.javaapi;

import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

public class JavadocPageParserTest {

    private final JavadocPageParser parser = new JavadocPageParser();

    @Test
    void testSmallInterface() throws IOException {
        JavadocClassDoc classDoc = parser.parse(loadResource("DataManager.html"));

        assertThat(classDoc.packageName()).isEqualTo("io.jmix.core");
        assertThat(classDoc.title()).isEqualTo("Interface DataManager");
        assertThat(classDoc.className()).isEqualTo("DataManager");
        assertThat(classDoc.fullyQualifiedName()).isEqualTo("io.jmix.core.DataManager");
        assertThat(classDoc.typeSignature())
                .isEqualTo("public interface DataManager extends UnconstrainedDataManager");
        assertThat(classDoc.description())
                .startsWith("Same as UnconstrainedDataManager but performs authorization");

        assertThat(classDoc.notes()).extracting(JavadocClassDoc.Note::label)
                .contains("All Superinterfaces:", "All Known Implementing Classes:");

        assertThat(classDoc.fields()).isEmpty();
        assertThat(classDoc.constructors()).isEmpty();
        assertThat(classDoc.methods()).hasSize(1);

        JavadocClassDoc.Member method = classDoc.methods().get(0);
        assertThat(method.modifierAndType()).isEqualTo("UnconstrainedDataManager");
        assertThat(method.signature()).isEqualTo("unconstrained()");
        assertThat(method.description()).startsWith("A convenience method");

        assertThat(classDoc.inheritedMembers()).hasSize(1);
        assertThat(classDoc.inheritedMembers().get(0))
                .contains("UnconstrainedDataManager")
                .contains("create")
                .contains("saveAll");
    }

    @Test
    void filtersInternalImplementingClassesWithoutDroppingOtherNotes() throws IOException {
        JavadocClassDoc classDoc = parser.parse(
                loadResource("DataManager.html"), href -> !href.contains("impl/"));

        assertThat(classDoc.notes()).extracting(JavadocClassDoc.Note::label)
                .contains("All Superinterfaces:")
                .doesNotContain("All Known Implementing Classes:");
    }

    @Test
    void testClassWithFieldsAndConstructors() throws IOException {
        JavadocClassDoc classDoc = parser.parse(loadResource("FetchPlan.html"));

        assertThat(classDoc.packageName()).isEqualTo("io.jmix.core");
        assertThat(classDoc.className()).isEqualTo("FetchPlan");

        assertThat(classDoc.fields()).extracting(JavadocClassDoc.Member::signature)
                .contains("BASE", "entityClass");
        assertThat(classDoc.constructors()).hasSize(1);
        assertThat(classDoc.constructors().get(0).modifierAndType()).isEqualTo("protected");
        assertThat(classDoc.constructors().get(0).signature())
                .startsWith("FetchPlan").contains("entityClass");
        assertThat(classDoc.methods()).isNotEmpty();
    }

    @Test
    void testLargeInterfaceMethods() throws IOException {
        JavadocClassDoc classDoc = parser.parse(loadResource("UnconstrainedDataManager.html"));

        assertThat(classDoc.methods().size()).isGreaterThan(10);
        assertThat(classDoc.methods()).extracting(JavadocClassDoc.Member::signature)
                .anyMatch(s -> s.startsWith("save"))
                .anyMatch(s -> s.startsWith("load"));
        // descriptions must not contain raw HTML markup or non-breaking spaces
        assertThat(classDoc.methods()).allSatisfy(m -> {
            assertThat(m.render()).doesNotContain("<a", "href=", "\u00A0");
        });
    }

    @Test
    void testEnumConstants() throws IOException {
        JavadocClassDoc classDoc = parser.parse(loadResource("JmixVersion.html"));

        assertThat(classDoc.title()).isEqualTo("Enum Class JmixVersion");
        assertThat(classDoc.fields()).extracting(JavadocClassDoc.Member::render)
                .containsExactly(
                        "V2 // Jmix 2.x.",
                        "V3 // Jmix 3.x.");
    }

    @Test
    void testAnnotationElements() throws IOException {
        JavadocClassDoc classDoc = parser.parse(loadResource("Subscribe.html"));

        assertThat(classDoc.title()).isEqualTo("Annotation Interface Subscribe");
        assertThat(classDoc.methods()).extracting(JavadocClassDoc.Member::render)
                .containsExactly(
                        "String id // ID of the event source component.",
                        "Target target // Target object that publishes the event.");
    }

    @Test
    void parsesRequiredAndOptionalAnnotationElementSections() {
        String html = """
                <section class="member-summary" id="annotation-interface-required-element-summary">
                    <div class="summary-table">
                        <div class="col-first">String</div>
                        <div class="col-second">requiredValue</div>
                        <div class="col-last">Required value.</div>
                    </div>
                </section>
                <section class="member-summary" id="annotation-interface-optional-element-summary">
                    <div class="summary-table">
                        <div class="col-first">boolean</div>
                        <div class="col-second">enabled</div>
                        <div class="col-last">Whether it is enabled.</div>
                    </div>
                </section>
                """;

        JavadocClassDoc classDoc = parser.parse(html);

        assertThat(classDoc.methods()).extracting(JavadocClassDoc.Member::render)
                .containsExactly(
                        "String requiredValue // Required value.",
                        "boolean enabled // Whether it is enabled.");
    }

    @Test
    void testClassNameStripsGenericsWithSpaces() {
        JavadocClassDoc classDoc = new JavadocClassDoc(
                "io.jmix.flowui.action.binder",
                "Class AbstractActionBindingImpl<H extends Component,A extends Action>",
                "public class AbstractActionBindingImpl", "",
                java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of());

        assertThat(classDoc.className()).isEqualTo("AbstractActionBindingImpl");
        assertThat(classDoc.fullyQualifiedName())
                .isEqualTo("io.jmix.flowui.action.binder.AbstractActionBindingImpl");
    }

    private String loadResource(String name) throws IOException {
        return IOUtils.toString(
                Objects.requireNonNull(getClass().getResourceAsStream(name), "missing resource " + name),
                StandardCharsets.UTF_8);
    }
}
