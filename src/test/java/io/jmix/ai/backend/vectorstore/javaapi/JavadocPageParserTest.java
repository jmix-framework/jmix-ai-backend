package io.jmix.ai.backend.vectorstore.javaapi;

import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * The fixtures under {@code v2/} and {@code v3/} are the verbatim live Javadoc pages of the same
 * types from Jmix 2.8 (javadoc 17) and 3.0 (javadoc 21) — kept 1:1 with docs.jmix.io so the parser
 * is exercised against the real markup of both toolchains. {@code JmixVersion.html} is a synthetic
 * minimal enum page (that class is not published in the Javadoc) used to cover enum constants.
 */
public class JavadocPageParserTest {

    private final JavadocPageParser parser = new JavadocPageParser();

    /** file, fully-qualified name, title, #fields, #constructors, #methods, #inherited lists. */
    static Stream<Arguments> realClasses() {
        return Stream.of(
                arguments("DataManager.html", "io.jmix.core.DataManager", "Interface DataManager",
                        0, 0, 1, 1),
                arguments("FetchPlan.html", "io.jmix.core.FetchPlan", "Class FetchPlan",
                        7, 1, 11, 1),
                arguments("UnconstrainedDataManager.html", "io.jmix.core.UnconstrainedDataManager",
                        "Interface UnconstrainedDataManager", 0, 0, 19, 0),
                arguments("Subscribe.html", "io.jmix.flowui.view.Subscribe", "Annotation Interface Subscribe",
                        0, 0, 5, 0));
    }

    /**
     * The same page parses to the same structure in both Jmix versions, i.e. the parser is robust
     * to the markup differences between javadoc 17 (2.8) and javadoc 21 (3.0).
     */
    @ParameterizedTest(name = "{2}")
    @MethodSource("realClasses")
    void parsesSamePageIdenticallyInBothJmixVersions(String file, String fullyQualifiedName, String title,
                                                     int fields, int constructors, int methods, int inherited) {
        JavadocClassDoc v2 = parser.parse(loadResource("v2/" + file));
        JavadocClassDoc v3 = parser.parse(loadResource("v3/" + file));

        for (JavadocClassDoc classDoc : List.of(v2, v3)) {
            assertThat(classDoc.title()).isEqualTo(title);
            assertThat(classDoc.fullyQualifiedName()).isEqualTo(fullyQualifiedName);
            assertThat(classDoc.fields()).hasSize(fields);
            assertThat(classDoc.constructors()).hasSize(constructors);
            assertThat(classDoc.methods()).hasSize(methods);
            assertThat(classDoc.inheritedMembers()).hasSize(inherited);
        }
        // the same set of members (by name) is seen in both versions; their signatures may
        // differ only by 3.0's nullability annotations (asserted separately)
        assertThat(memberNames(v3)).isEqualTo(memberNames(v2));
    }

    /**
     * Jmix 3.0 annotates nullability, so the same core type carries {@code @NullMarked} in its 3.0
     * signature but not in 2.8 — a real markup difference the parser must keep verbatim.
     */
    @ParameterizedTest
    @ValueSource(strings = {"DataManager.html", "UnconstrainedDataManager.html"})
    void keepsNullnessAnnotationThatOnlyV3Carries(String file) {
        assertThat(parser.parse(loadResource("v2/" + file)).typeSignature())
                .doesNotContain("@NullMarked");
        assertThat(parser.parse(loadResource("v3/" + file)).typeSignature())
                .startsWith("@NullMarked ");
    }

    @Test
    void keepsParameterNullnessAnnotationThatOnlyV3Carries() {
        JavadocClassDoc v2 = parser.parse(loadResource("v2/FetchPlan.html"));
        JavadocClassDoc v3 = parser.parse(loadResource("v3/FetchPlan.html"));

        assertThat(signatureOf(v2, "contentEquals")).isEqualTo("contentEquals(FetchPlan that)");
        assertThat(signatureOf(v3, "contentEquals")).isEqualTo("contentEquals(@Nullable FetchPlan that)");
    }

    /**
     * The class Javadoc of {@code @Subscribe} carries its usage example in a {@code <pre>} block,
     * whose newlines {@code Element.text()} preserves — the parser must collapse them because a
     * description spans exactly one {@code DESCRIPTION:} card line ({@code Snippet.parse} keeps
     * only the first line on the enrichment round-trip).
     */
    @ParameterizedTest
    @ValueSource(strings = {"v2", "v3"})
    void collapsesPreBlockNewlinesInDescription(String version) {
        JavadocClassDoc classDoc = parser.parse(loadResource(version + "/Subscribe.html"));

        assertThat(classDoc.description())
                .doesNotContain("\n")
                .contains("Example: @Subscribe(\"demoButton\") protected void onDemoButtonClick");
    }

    private static String signatureOf(JavadocClassDoc classDoc, String memberName) {
        return classDoc.methods().stream()
                .map(JavadocClassDoc.Member::signature)
                .filter(signature -> signature.startsWith(memberName + "("))
                .findFirst()
                .orElseThrow();
    }

    // --- Full-page parses on the real 2.8 pages: whole JavadocClassDoc at once, per page kind ---

    @Test
    void fullyParsesInterfaceWithMethodNotesAndInheritedMembers() {
        JavadocClassDoc classDoc = parser.parse(loadResource("v2/DataManager.html"));

        assertThat(classDoc.typeSignature())
                .isEqualTo("public interface DataManager extends UnconstrainedDataManager");
        assertThat(classDoc.description())
                .startsWith("Same as UnconstrainedDataManager but performs authorization");
        assertThat(classDoc.notes()).extracting(JavadocClassDoc.Note::label)
                .containsExactly("All Superinterfaces:", "All Known Implementing Classes:");
        assertThat(classDoc.fields()).isEmpty();
        assertThat(classDoc.constructors()).isEmpty();

        assertThat(classDoc.methods()).singleElement().satisfies(method -> {
            assertThat(method.modifierAndType()).isEqualTo("UnconstrainedDataManager");
            assertThat(method.signature()).isEqualTo("unconstrained()");
            assertThat(method.description()).startsWith("A convenience method");
        });

        assertThat(classDoc.inheritedMembers()).singleElement().asString()
                .contains("UnconstrainedDataManager")
                .contains("create")
                .contains("saveAll");
    }

    @Test
    void fullyParsesConcreteClassWithFieldsAndConstructor() {
        JavadocClassDoc classDoc = parser.parse(loadResource("v2/FetchPlan.html"));

        assertThat(classDoc.typeSignature())
                .isEqualTo("public class FetchPlan extends Object implements Serializable");
        assertThat(classDoc.notes()).extracting(JavadocClassDoc.Note::label)
                .containsExactly("All Implemented Interfaces:", "See Also:");

        assertThat(classDoc.fields()).extracting(JavadocClassDoc.Member::signature)
                .containsExactly("BASE", "entityClass", "INSTANCE_NAME", "loadPartialEntities",
                        "LOCAL", "name", "properties");
        assertThat(classDoc.fields()).filteredOn(f -> f.signature().equals("BASE")).singleElement()
                .satisfies(base -> {
                    assertThat(base.modifierAndType()).isEqualTo("static final String");
                    assertThat(base.description()).startsWith("Includes all local properties");
                });

        assertThat(classDoc.constructors()).singleElement().satisfies(constructor -> {
            assertThat(constructor.modifierAndType()).isEqualTo("protected");
            assertThat(constructor.signature()).startsWith("FetchPlan").contains("entityClass");
        });

        assertThat(classDoc.methods()).hasSize(11);
    }

    @Test
    void fullyParsesAnnotationInterface() {
        JavadocClassDoc classDoc = parser.parse(loadResource("v2/Subscribe.html"));

        assertThat(classDoc.typeSignature())
                .isEqualTo("@Documented @Retention(RUNTIME) @Target(METHOD) public @interface Subscribe");
        assertThat(classDoc.fields()).isEmpty();
        assertThat(classDoc.constructors()).isEmpty();
        assertThat(classDoc.inheritedMembers()).isEmpty();
        assertThat(classDoc.methods()).extracting(JavadocClassDoc.Member::signature)
                .containsExactly("id", "required", "subject", "target", "value");
        assertThat(classDoc.methods()).filteredOn(m -> m.signature().equals("required")).singleElement()
                .satisfies(required -> {
                    assertThat(required.modifierAndType()).isEqualTo("boolean");
                    assertThat(required.description()).startsWith("Declares whether");
                });
    }

    @Test
    void fullyParsesSyntheticEnumClass() {
        JavadocClassDoc classDoc = parser.parse(loadResource("JmixVersion.html"));

        assertThat(classDoc.title()).isEqualTo("Enum Class JmixVersion");
        assertThat(classDoc.typeSignature()).isEqualTo("public enum JmixVersion");
        assertThat(classDoc.notes()).isEmpty();
        assertThat(classDoc.constructors()).isEmpty();
        assertThat(classDoc.methods()).isEmpty();
        assertThat(classDoc.inheritedMembers()).isEmpty();
        assertThat(classDoc.fields()).extracting(JavadocClassDoc.Member::render)
                .containsExactly("V2 // Jmix 2.x.", "V3 // Jmix 3.x.");
    }

    // --- Focused behaviours ---

    @Test
    void filtersInternalImplementingClassesWithoutDroppingOtherNotes() {
        JavadocClassDoc classDoc = parser.parse(
                loadResource("v2/DataManager.html"), href -> !href.contains("impl/"));

        assertThat(classDoc.notes()).extracting(JavadocClassDoc.Note::label)
                .contains("All Superinterfaces:")
                .doesNotContain("All Known Implementing Classes:");
    }

    @Test
    void largeInterfaceMethodDescriptionsAreStrippedOfMarkup() {
        JavadocClassDoc classDoc = parser.parse(loadResource("v2/UnconstrainedDataManager.html"));

        assertThat(classDoc.methods()).hasSize(19);
        assertThat(classDoc.methods()).extracting(JavadocClassDoc.Member::signature)
                .anyMatch(s -> s.startsWith("save"))
                .anyMatch(s -> s.startsWith("load"));
        // descriptions must not carry raw HTML markup or non-breaking spaces into the card
        assertThat(classDoc.methods()).allSatisfy(method ->
                assertThat(method.render()).doesNotContain("<a", "href=", "\u00A0"));
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

    @ParameterizedTest
    @MethodSource("nonClassPages")
    void returnsBlankSignatureForNonClassPages(String html) {
        JavadocClassDoc classDoc = parser.parse(html);

        assertThat(classDoc.typeSignature()).isBlank();
        assertThat(classDoc.methods()).isEmpty();
        assertThat(classDoc.fields()).isEmpty();
    }

    static Stream<Arguments> nonClassPages() {
        return Stream.of(
                arguments(""),
                arguments("<html><body><p>Not a Javadoc page</p></body></html>"),
                arguments("<html><body><div class=\"header\"><h1 class=\"title\">Package io.jmix.core</h1>"
                        + "</div></body></html>"));
    }

    @Test
    void classNameStripsGenericsWithSpaces() {
        JavadocClassDoc classDoc = new JavadocClassDoc(
                "io.jmix.flowui.action.binder",
                "Class AbstractActionBindingImpl<H extends Component,A extends Action>",
                "public class AbstractActionBindingImpl", "",
                List.of(), List.of(), List.of(), List.of(), List.of());

        assertThat(classDoc.className()).isEqualTo("AbstractActionBindingImpl");
        assertThat(classDoc.fullyQualifiedName())
                .isEqualTo("io.jmix.flowui.action.binder.AbstractActionBindingImpl");
    }

    /** Member name only — the signature up to the parameter list, so nullability annotations
     *  on parameters do not count as a difference between versions. */
    private static List<String> memberNames(JavadocClassDoc classDoc) {
        return Stream.of(classDoc.fields(), classDoc.constructors(), classDoc.methods())
                .flatMap(List::stream)
                .map(member -> {
                    String signature = member.signature();
                    int paramStart = signature.indexOf('(');
                    return paramStart < 0 ? signature : signature.substring(0, paramStart);
                })
                .toList();
    }

    private String loadResource(String name) {
        try {
            return IOUtils.toString(
                    Objects.requireNonNull(getClass().getResourceAsStream(name), "missing resource " + name),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
