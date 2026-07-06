package io.jmix.ai.backend.vectorstore.snippets;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DocsHtmlConverterTest {

    @Test
    void convertsHtmlToTextWithVerbatimCode() {
        String html = """
                <article class="doc">
                <h1>Button</h1>
                <p>The <code>button</code> component &lt;triggers&gt; actions.</p>
                <ul><li>First item</li><li>Second item</li></ul>
                <div class="listingblock"><pre class="highlightjs">&lt;button id="helloButton"
                        text="Say Hello"/&gt; <i class="conum" data-value="1"></i><b>(1)</b></pre></div>
                <p>After code.</p>
                </article>
                """;

        String text = DocsHtmlConverter.toPlainText(html);

        assertThat(text)
                .contains("Button")
                .contains("The button component <triggers> actions.")
                .contains("- First item")
                .contains("- Second item")
                .contains("After code.")
                .doesNotContain("<p>")
                .doesNotContain("&lt;")
                .doesNotContain("conum")
                .doesNotContain("(1)");
        assertThat(text).contains("""
                ```
                <button id="helloButton"
                        text="Say Hello"/>
                ```""");
    }

    @Test
    void collapsesWhitespaceOutsideCode() {
        String text = DocsHtmlConverter.toPlainText("<p>a    b</p><p>c&nbsp;d</p>");

        assertThat(text).isEqualTo("a b\n\nc d");
    }
}
