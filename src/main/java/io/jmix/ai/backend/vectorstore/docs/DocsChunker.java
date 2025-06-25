package io.jmix.ai.backend.vectorstore.docs;

import io.jmix.ai.backend.vectorstore.Chunker;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class DocsChunker implements Chunker {

    private static final Logger log = LoggerFactory.getLogger(DocsChunker.class);

    public final int maxChunkSize;
    public final int minDocPreambleSize;
    public final int minSect1PreambleSize;

    public DocsChunker(int maxChunkSize, int minDocPreambleSize, int minSect1PreambleSize) {
        this.maxChunkSize = maxChunkSize;
        this.minDocPreambleSize = minDocPreambleSize;
        this.minSect1PreambleSize = minSect1PreambleSize;
    }

    public List<Chunk> extract(String content) {
        List<Chunk> chunks = new ArrayList<>();

        Document document = Jsoup.parse(content);
        Element articleEl = document.select("article.doc").first();
        if (articleEl == null) {
            log.warn("No article element found in the HTML content");
            return chunks;
        }
        Element docTitleEl = articleEl.select("h1").first();
        if (docTitleEl == null) {
            log.warn("No doc title element found in the HTML content");
            return chunks;
        }
        String docTitle = docTitleEl.text();

        Elements sect1Elements = articleEl.select("div.sect1");
        if (!sect1Elements.isEmpty()) {
            Elements preambleElements = articleEl.select("div#preamble");
            String preambleText = preambleElements.text();
            if (preambleText.length() >= minDocPreambleSize) {
                String chunkTitle = createChunkTitle(docTitle);
                chunks.add(new Chunk(getTextWithHeader(preambleText, chunkTitle), chunkTitle, null));
            } else {
                log.debug("Skipping doc '{}' preamble because it is too short", docTitle);
            }

            for (Element s1El : sect1Elements) {
                Element s1TitleEl = s1El.select("h2").first();
                if (s1TitleEl == null) {
                    log.warn("Skipping doc '{}' sect1 because it has no title", docTitle);
                    continue;
                }
                String s1Title = s1TitleEl.text();
                String s1Anchor = getAnchor(s1TitleEl);
                s1TitleEl.remove();
                String s1Text = s1El.text();
                if (s1Text.length() <= maxChunkSize) {
                    String chunkTitle = createChunkTitle(docTitle, s1Title);
                    chunks.add(new Chunk(getTextWithHeader(s1Text, chunkTitle), chunkTitle, s1Anchor));
                } else {
                    Element s1ContentEl = s1El.select("div.sectionbody").first();
                    if (s1ContentEl == null) {
                        s1ContentEl = s1El;
                    }
                    Elements s1PreambleElements = s1ContentEl.children().not("div.sect2");
                    String s1PreambleText = s1PreambleElements.text();
                    if (s1PreambleText.length() >= minSect1PreambleSize) {
                        String chunkTitle = createChunkTitle(docTitle, s1Title);
                        chunks.add(new Chunk(getTextWithHeader(s1PreambleText, chunkTitle), chunkTitle, s1Anchor));
                    }

                    Elements s2Elements = s1ContentEl.select("div.sect2");
                    for (Element s2El : s2Elements) {
                        Element s2TitleEl = s2El.select("h3").first();
                        if (s2TitleEl == null) {
                            log.warn("Skipping doc '{}' sect2 '{}' because it has no title", docTitle, s1Title);
                            continue;
                        }
                        String s2Anchor = getAnchor(s2TitleEl);
                        s2TitleEl.remove();
                        String s2Text = s2El.text();
                        String chunkTitle = createChunkTitle(docTitle, s1Title, s2TitleEl.text());
                        if (s2Text.length() < maxChunkSize) {
                            chunks.add(new Chunk(getTextWithHeader(s2Text, chunkTitle), chunkTitle, s2Anchor));
                        } else {
                            log.warn("Skipping chunk with title '{}' because it is too long", chunkTitle);
                        }
                    }
                    
                }
            }
        } else {
            docTitleEl.remove();
            if (articleEl.text().length() >= minDocPreambleSize) {
                chunks.add(new Chunk(getTextWithHeader(articleEl.text(), docTitle), docTitle, null));
            }
        }

        return chunks;
    }

    private String getAnchor(Element el) {
        return el.select("a.anchor").attr("href");
    }

    private String getTextWithHeader(String text, String title) {
        return "# " + title + "\n" + text;
    }

    private String createChunkTitle(String... titles) {
        StringBuilder sb = new StringBuilder();
        for (String s : titles) {
            if (s != null && !s.isBlank()) {
                if (!sb.isEmpty()) {
                    if (!s.endsWith(".") && !s.endsWith("?") && !s.endsWith("!"))
                        sb.append(".");
                    sb.append(" ");
                }
                sb.append(s);
            }
        }
        return sb.toString();
    }
}
