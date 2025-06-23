/**
 * Based on {@code AsciiDocPreprocessor} from https://github.com/m0mus/helidon-assistant
 */
package io.jmix.ai.backend.vectorstore.trainings;

import org.asciidoctor.*;
import org.asciidoctor.ast.*;

import java.util.*;
import java.util.List;

public class AsciidocPreprocessor {

    private final Asciidoctor asciidoctor;

    public AsciidocPreprocessor() {
        this.asciidoctor = Asciidoctor.Factory.create();
    }

    public List<AsciidocBlock> extractBlocks(String content/*, File baseDir*/) {
        Options options = Options.builder()
//                .baseDir(baseDir)                  // enables include:: to resolve
                .safe(SafeMode.UNSAFE)             // allows full access (use cautiously)
                .build();

        Document doc = asciidoctor.load(content, options);
        List<AsciidocBlock> blocks = new ArrayList<>();
        walk(doc, blocks, new ArrayDeque<>());
        return blocks;
    }

    private void walk(StructuralNode node, List<AsciidocBlock> blocks, Deque<String> sectionStack) {
        if (node instanceof Section) {
            sectionStack.push(node.getTitle());
        }

        String context = node.getContext();
        Object content = node.getContent();
        String sectionPath = joinSection(sectionStack);

        switch (context) {
            case "paragraph" -> {
                if (!content.toString().startsWith("Unresolved directive in ")) {
                    blocks.add(new AsciidocBlock(transformParagraph(content.toString()), AsciidocBlock.Type.PARAGRAPH, sectionPath));
                }
            }
            case "listing" ->
                    blocks.add(new AsciidocBlock(transformCode(content.toString()), AsciidocBlock.Type.CODE, sectionPath));
            case "table" ->
                    blocks.add(new AsciidocBlock(tableToText((Table) node), AsciidocBlock.Type.TABLE, sectionPath));
            case "ulist", "olist" ->
                    blocks.add(new AsciidocBlock(listToText(node), AsciidocBlock.Type.LIST, sectionPath));
        }

        for (StructuralNode child : node.getBlocks()) {
            walk(child, blocks, sectionStack);
        }

        if (node instanceof Section) {
            sectionStack.pop();
        }
    }

    private String joinSection(Deque<String> sections) {
        List<String> list = new ArrayList<>(sections);
        Collections.reverse(list);
        return list.isEmpty() ? "" : String.join(". ", list);
    }

    private String transformParagraph(String text) {
        return text
                .replaceAll("<code>(.*?)</code>", "`$1`")
                .replaceAll("<strong>(.*?)</strong>", "**$1**")
                .replaceAll("<em>(.*?)</em>", "*$1*")
                .replaceAll("<a.*?>(.*?)</a>", "$1")
                .replaceAll("&lt;", "<")
                .replaceAll("&gt;", ">")
                .replaceAll("&#8217;", "'")
                .trim();
    }

    private String transformCode(String text) {
        String s = text.replaceAll("&lt;", "<").replaceAll("&gt;", ">");
        return "```\n" + s + "\n```\n";
    }

    private String tableToText(Table table) {
        StringBuilder sb = new StringBuilder();
        for (Row row : table.getBody()) {
            List<String> cols = new ArrayList<>();
            for (Cell cell : row.getCells()) {
                cols.add(cell.getText());
            }
            sb.append(String.join(" | ", cols)).append("\n");
        }
        return sb.toString().trim();
    }

    private String listToText(StructuralNode listNode) {
        StringBuilder sb = new StringBuilder();

        org.asciidoctor.ast.List list = (org.asciidoctor.ast.List) listNode;
        for (Object item : list.getItems()) {
            ListItem listItem = (ListItem) item;
            sb.append("- ").append(transformParagraph(listItem.getText())).append("\n");
        }
        return sb.toString().trim();
    }
}
