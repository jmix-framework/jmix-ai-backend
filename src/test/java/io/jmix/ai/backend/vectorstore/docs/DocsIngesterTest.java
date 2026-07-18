package io.jmix.ai.backend.vectorstore.docs;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DocsIngesterTest {

    @Test
    void topicFromSource_UsesFirstPathSegment() {
        assertThat(DocsIngester.topicFromSource("flowui/vc/components/button.html")).isEqualTo("flowui");
        assertThat(DocsIngester.topicFromSource("ai-tools/index.html")).isEqualTo("ai-tools");
    }

    @Test
    void topicFromSource_StripsHtmlSuffixOfTopLevelPages() {
        assertThat(DocsIngester.topicFromSource("account-management.html")).isEqualTo("account-management");
        assertThat(DocsIngester.topicFromSource("whatsnew.html#anchor")).isEqualTo("whatsnew");
    }
}
