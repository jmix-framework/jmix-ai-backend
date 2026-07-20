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

    @Test
    void keepsTextAndMultipleCodeBlocksInOrder() {
        String text = DocsHtmlConverter.toPlainText(
                "<p>Before</p><pre>code one</pre><p>Middle</p><pre>code two</pre><p>After</p>");

        assertThat(text).isEqualTo("""
                Before

                ```
                code one
                ```

                Middle

                ```
                code two
                ```

                After""");
    }

    @Test
    void preservesCodeIndentationVerbatim() {
        String text = DocsHtmlConverter.toPlainText("<pre>class A {\n    void m() {\n        x();\n    }\n}</pre>");

        assertThat(text).isEqualTo("""
                ```
                class A {
                    void m() {
                        x();
                    }
                }
                ```""");
    }

    @Test
    void dropsNumericCalloutsInsideCodeButKeepsOtherBold() {
        String text = DocsHtmlConverter.toPlainText(
                "<pre>a <i class=\"conum\"></i><b>(1)</b> b <b>keep</b></pre>");

        assertThat(text)
                .doesNotContain("conum")
                .doesNotContain("(1)")
                .contains("keep");
    }

    @Test
    void leavesNumericBoldOutsideCodeAlone() {
        String text = DocsHtmlConverter.toPlainText("<p>step <b>(1)</b> here</p>");

        assertThat(text).isEqualTo("step (1) here");
    }

    @Test
    void dropsMultiDigitCallouts() {
        String text = DocsHtmlConverter.toPlainText("<pre>z <b>(12)</b></pre>");

        assertThat(text).isEqualTo("""
                ```
                z
                ```""");
    }

    @Test
    void breakBecomesNewline() {
        assertThat(DocsHtmlConverter.toPlainText("<p>a<br>b</p>")).isEqualTo("a\n\nb");
    }

    @Test
    void headingsSeparateSections() {
        String text = DocsHtmlConverter.toPlainText("<h2>Sec</h2><p>x</p><h3>Sub</h3><p>y</p>");

        assertThat(text).isEqualTo("Sec\n\nx\n\nSub\n\ny");
    }

    @Test
    void unwrapsInlineFormattingTagsToTheirText() {
        String text = DocsHtmlConverter.toPlainText("<p><strong>a</strong> <em>b</em> <code>c</code></p>");

        assertThat(text).isEqualTo("a b c");
    }

    @Test
    void trimsSurroundingWhitespace() {
        assertThat(DocsHtmlConverter.toPlainText("  <p>  hi  </p>  ")).isEqualTo("hi");
    }

    @Test
    void handlesEmptyInput() {
        assertThat(DocsHtmlConverter.toPlainText("")).isEmpty();
    }
}
