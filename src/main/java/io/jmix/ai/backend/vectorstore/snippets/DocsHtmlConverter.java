package io.jmix.ai.backend.vectorstore.snippets;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;

/**
 * Converts a documentation page (HTML) into plain text with verbatim fenced code blocks.
 * Feeding this to the snippetizer instead of raw HTML prevents markup and entity artifacts
 * from leaking into generated snippets, and cuts the input token count.
 */
public class DocsHtmlConverter {

    public static String toPlainText(String html) {
        org.jsoup.nodes.Document doc = Jsoup.parse(html);

        // drop Antora callout markers inside code blocks: <i class="conum"/> and "(1)" bullets
        doc.select("pre i.conum").remove();
        for (Element b : doc.select("pre b")) {
            if (b.text().matches("\\(\\d+\\)")) {
                b.remove();
            }
        }

        StringBuilder out = new StringBuilder();
        StringBuilder textBuf = new StringBuilder();

        NodeTraversor.traverse(new NodeVisitor() {
            @Override
            public void head(Node node, int depth) {
                if (node instanceof Element el) {
                    String tag = el.normalName();
                    if (tag.equals("pre")) {
                        flushText();
                        out.append("\n```\n").append(el.wholeText().strip()).append("\n```\n");
                    } else if (tag.matches("h[1-6]")) {
                        textBuf.append("\n\n");
                    } else if (tag.equals("li")) {
                        textBuf.append("\n- ");
                    } else if (el.isBlock() || tag.equals("br")) {
                        textBuf.append('\n');
                    }
                } else if (node instanceof TextNode textNode && !insidePre(textNode)) {
                    textBuf.append(textNode.text());
                }
            }

            @Override
            public void tail(Node node, int depth) {
                if (node instanceof Element el && el.isBlock()) {
                    textBuf.append('\n');
                }
            }

            private boolean insidePre(Node node) {
                for (Node parent = node.parent(); parent != null; parent = parent.parent()) {
                    if (parent instanceof Element el && el.normalName().equals("pre")) {
                        return true;
                    }
                }
                return false;
            }

            private void flushText() {
                out.append(normalize(textBuf.toString()));
                textBuf.setLength(0);
            }
        }, doc);

        out.append(normalize(textBuf.toString()));
        return out.toString().replaceAll("\n{3,}", "\n\n").trim();
    }

    private static String normalize(String text) {
        return text
                .replace('\u00A0', ' ')
                .replaceAll("[ \\t]+", " ")
                .replaceAll(" ?\\n ?", "\n")
                .replaceAll("\n{3,}", "\n\n");
    }
}
