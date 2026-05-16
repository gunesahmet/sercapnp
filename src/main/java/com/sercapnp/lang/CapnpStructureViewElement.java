package com.sercapnp.lang;

import com.intellij.ide.structureView.StructureViewTreeElement;
import com.intellij.ide.util.treeView.smartTree.SortableTreeElement;
import com.intellij.ide.util.treeView.smartTree.TreeElement;
import com.intellij.navigation.ItemPresentation;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.util.IconLoader;
import com.intellij.psi.PsiFile;
import com.intellij.psi.NavigatablePsiElement;
import com.intellij.util.PlatformIcons;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CapnpStructureViewElement implements StructureViewTreeElement, SortableTreeElement {

    private final PsiFile file;
    private final NodeInfo nodeInfo;

    // Root constructor
    public CapnpStructureViewElement(@NotNull PsiFile file) {
        this.file = file;
        this.nodeInfo = null;
    }

    // Child constructor
    private CapnpStructureViewElement(@NotNull PsiFile file, @NotNull NodeInfo info) {
        this.file = file;
        this.nodeInfo = info;
    }

    @Override
    public Object getValue() {
        return file;
    }

    @NotNull
    @Override
    public String getAlphaSortKey() {
        return nodeInfo != null ? nodeInfo.name : file.getName();
    }

    @Override
    public void navigate(boolean requestFocus) {
        if (nodeInfo != null && file.getVirtualFile() != null) {
            new OpenFileDescriptor(file.getProject(), file.getVirtualFile(), nodeInfo.offset)
                    .navigate(requestFocus);
        }
    }

    @Override
    public boolean canNavigate() {
        return nodeInfo != null;
    }

    @Override
    public boolean canNavigateToSource() {
        return canNavigate();
    }

    @NotNull
    @Override
    public ItemPresentation getPresentation() {
        if (nodeInfo != null) {
            return new ItemPresentation() {
                @Nullable @Override
                public String getPresentableText() {
                    return nodeInfo.displayText;
                }
                @Nullable @Override
                public String getLocationString() {
                    return nodeInfo.detail;
                }
                @Nullable @Override
                public Icon getIcon(boolean unused) {
                    return nodeInfo.icon;
                }
            };
        }

        // Root element
        return new ItemPresentation() {
            @Nullable @Override
            public String getPresentableText() { return file.getName(); }
            @Nullable @Override
            public String getLocationString() { return null; }
            @Nullable @Override
            public Icon getIcon(boolean unused) { return CapnpFileType.INSTANCE.getIcon(); }
        };
    }

    @NotNull
    @Override
    public TreeElement[] getChildren() {
        if (nodeInfo != null && nodeInfo.children != null) {
            List<TreeElement> elements = new ArrayList<>();
            for (NodeInfo child : nodeInfo.children) {
                elements.add(new CapnpStructureViewElement(file, child));
            }
            return elements.toArray(new TreeElement[0]);
        }

        if (nodeInfo == null) {
            // Root — parse the file
            List<NodeInfo> nodes = parseFile(file.getText());
            List<TreeElement> elements = new ArrayList<>();
            for (NodeInfo node : nodes) {
                elements.add(new CapnpStructureViewElement(file, node));
            }
            return elements.toArray(new TreeElement[0]);
        }

        return TreeElement.EMPTY_ARRAY;
    }

    // ── File parsing ────────────────────────────────────────────────────────────

    private static final Pattern TYPE_DEF = Pattern.compile(
            "\\b(struct|enum|interface)\\s+([A-Z][A-Za-z0-9]*)");
    private static final Pattern FIELD_DEF = Pattern.compile(
            "^(\\s+)([a-z][a-zA-Z0-9]*)\\s+@(\\d+)\\s*:\\s*(.+?)\\s*(?:=|;|\\$)",
            Pattern.MULTILINE);
    private static final Pattern CONST_DEF = Pattern.compile(
            "^\\s*const\\s+([a-zA-Z][a-zA-Z0-9]*)\\s*:\\s*(\\S+)",
            Pattern.MULTILINE);
    private static final Pattern UNION_GROUP = Pattern.compile(
            "\\b(union|group)\\s*\\{|([a-z][a-zA-Z0-9]*)\\s*:\\s*(union|group)\\s*(?:\\$[^{]*)?\\{");

    static class NodeInfo {
        final String kind;        // "struct", "enum", "interface", "field", "const", "union", "group", "enumerant", "method"
        final String name;
        final String displayText;
        final String detail;
        final int offset;
        final Icon icon;
        List<NodeInfo> children;

        NodeInfo(String kind, String name, String displayText, String detail, int offset, Icon icon) {
            this.kind = kind;
            this.name = name;
            this.displayText = displayText;
            this.detail = detail;
            this.offset = offset;
            this.icon = icon;
        }

        void addChild(NodeInfo child) {
            if (children == null) children = new ArrayList<>();
            children.add(child);
        }
    }

    /**
     * Parse file text into a tree of NodeInfo.
     */
    private static List<NodeInfo> parseFile(String text) {
        List<NodeInfo> roots = new ArrayList<>();
        parseBlock(text, 0, text.length(), roots, 0);
        return roots;
    }

    /**
     * Parse a block of text, finding definitions and building a tree.
     */
    private static void parseBlock(String text, int start, int end, List<NodeInfo> out, int depth) {
        int i = start;

        while (i < end) {
            // Skip whitespace and comments
            i = skipWhitespaceAndComments(text, i, end);
            if (i >= end) break;

            char c = text.charAt(i);

            // Check for struct/enum/interface
            if (c == 's' || c == 'e' || c == 'i') {
                Matcher m = TYPE_DEF.matcher(text);
                if (m.find(i) && m.start() == i) {
                    String kind = m.group(1);
                    String name = m.group(2);

                    // Find the opening brace
                    int bracePos = findNextBrace(text, m.end(), end);
                    if (bracePos >= 0) {
                        int closePos = findMatchingClose(text, bracePos);
                        if (closePos > 0) {
                            Icon icon = getIconForKind(kind);
                            NodeInfo node = new NodeInfo(kind, name, name, kind, i, icon);

                            // Parse children inside the block
                            parseBlockBody(text, bracePos + 1, closePos, node, kind, depth + 1);

                            out.add(node);
                            i = closePos + 1;
                            continue;
                        }
                    }
                }
            }

            // Check for const
            if (c == 'c' && text.startsWith("const ", i)) {
                Matcher m = CONST_DEF.matcher(text);
                if (m.find(i) && m.start() == i) {
                    String name = m.group(1);
                    String type = m.group(2);
                    out.add(new NodeInfo("const", name, name, ": " + type, i,
                            PlatformIcons.VARIABLE_ICON));

                    int semi = text.indexOf(';', m.end());
                    i = (semi >= 0) ? semi + 1 : m.end();
                    continue;
                }
            }

            // Skip to next line
            int nextLine = text.indexOf('\n', i);
            i = (nextLine >= 0) ? nextLine + 1 : end;
        }
    }

    /**
     * Parse the body of a struct/enum/interface block.
     */
    private static void parseBlockBody(String text, int start, int end,
                                        NodeInfo parent, String parentKind, int depth) {
        int i = start;

        while (i < end) {
            i = skipWhitespaceAndComments(text, i, end);
            if (i >= end) break;

            char c = text.charAt(i);

            // Nested struct/enum/interface
            if (c == 's' || c == 'e' || c == 'i') {
                Matcher m = TYPE_DEF.matcher(text);
                if (m.find(i) && m.start() == i) {
                    String kind = m.group(1);
                    String name = m.group(2);
                    int bracePos = findNextBrace(text, m.end(), end);
                    if (bracePos >= 0) {
                        int closePos = findMatchingClose(text, bracePos);
                        if (closePos > 0 && closePos <= end) {
                            NodeInfo node = new NodeInfo(kind, name, name, kind, i, getIconForKind(kind));
                            parseBlockBody(text, bracePos + 1, closePos, node, kind, depth + 1);
                            parent.addChild(node);
                            i = closePos + 1;
                            continue;
                        }
                    }
                }
            }

            // union { or name :union {
            if (c == 'u' && text.startsWith("union", i)) {
                int bracePos = findNextBrace(text, i + 5, Math.min(i + 100, end));
                if (bracePos >= 0) {
                    int closePos = findMatchingClose(text, bracePos);
                    if (closePos > 0 && closePos <= end) {
                        NodeInfo node = new NodeInfo("union", "union", "union", null, i,
                                PlatformIcons.ANONYMOUS_CLASS_ICON);
                        parseBlockBody(text, bracePos + 1, closePos, node, "struct", depth + 1);
                        parent.addChild(node);
                        i = closePos + 1;
                        continue;
                    }
                }
            }

            // Named union/group: name :union { or name :group {
            if (Character.isLetter(c)) {
                // Check for field with union/group type
                Pattern namedUG = Pattern.compile(
                        "([a-z][a-zA-Z0-9]*)\\s*:\\s*(union|group)\\s*(?:\\$[^{]*)?\\{");
                Matcher ugm = namedUG.matcher(text);
                if (ugm.find(i) && ugm.start() == i) {
                    String name = ugm.group(1);
                    String kind = ugm.group(2);
                    int bracePos = text.indexOf('{', ugm.start());
                    if (bracePos >= 0) {
                        int closePos = findMatchingClose(text, bracePos);
                        if (closePos > 0 && closePos <= end) {
                            NodeInfo node = new NodeInfo(kind, name, name, kind, i,
                                    PlatformIcons.ANONYMOUS_CLASS_ICON);
                            parseBlockBody(text, bracePos + 1, closePos, node, "struct", depth + 1);
                            parent.addChild(node);
                            i = closePos + 1;
                            continue;
                        }
                    }
                }

                // Regular field: name @N :Type;
                int lineEnd = text.indexOf('\n', i);
                if (lineEnd < 0) lineEnd = end;
                if (lineEnd > end) lineEnd = end;
                String line = text.substring(i, lineEnd);

                Matcher fm = Pattern.compile(
                        "([a-zA-Z][a-zA-Z0-9]*)\\s+@(\\d+)\\s*(?::\\s*(.+?))?\\s*(?:=.*)?;?$"
                ).matcher(line.trim());

                if (fm.find()) {
                    String name = fm.group(1);
                    String ordinal = fm.group(2);
                    String type = fm.group(3);

                    String detail = "@" + ordinal;
                    if (type != null) detail += " : " + type.trim();

                    Icon icon;
                    if ("enum".equals(parentKind)) {
                        icon = PlatformIcons.ENUM_ICON;
                    } else {
                        icon = PlatformIcons.FIELD_ICON;
                    }

                    parent.addChild(new NodeInfo("field", name, name, detail, i, icon));
                }
            }

            // Const inside a block
            if (c == 'c' && text.startsWith("const ", i)) {
                Matcher m = CONST_DEF.matcher(text);
                if (m.find(i) && m.start() <= i + 10) {
                    parent.addChild(new NodeInfo("const", m.group(1), m.group(1),
                            ": " + m.group(2), i, PlatformIcons.VARIABLE_ICON));
                    int semi = text.indexOf(';', m.end());
                    i = (semi >= 0) ? semi + 1 : m.end();
                    continue;
                }
            }

            // Skip to next line
            int nextLine = text.indexOf('\n', i);
            i = (nextLine >= 0) ? nextLine + 1 : end;
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private static int skipWhitespaceAndComments(String text, int pos, int end) {
        while (pos < end) {
            char c = text.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                pos++;
            } else if (c == '#') {
                // Skip to end of line
                while (pos < end && text.charAt(pos) != '\n') pos++;
            } else {
                break;
            }
        }
        return pos;
    }

    private static int findNextBrace(String text, int from, int end) {
        for (int i = from; i < end && i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') return i;
            if (c == ';') return -1; // Statement ended without brace
        }
        return -1;
    }

    private static int findMatchingClose(String text, int openPos) {
        int depth = 0;
        boolean inString = false;
        boolean inComment = false;

        for (int i = openPos; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inComment) { if (c == '\n') inComment = false; continue; }
            if (inString) {
                if (c == '\\') { i++; continue; }
                if (c == '"') inString = false;
                continue;
            }
            if (c == '#') { inComment = true; continue; }
            if (c == '"') { inString = true; continue; }
            if (c == '{') depth++;
            else if (c == '}') { depth--; if (depth == 0) return i; }
        }
        return -1;
    }

    private static Icon getIconForKind(String kind) {
        switch (kind) {
            case "struct": return PlatformIcons.CLASS_ICON;
            case "enum": return PlatformIcons.ENUM_ICON;
            case "interface": return PlatformIcons.INTERFACE_ICON;
            default: return PlatformIcons.CLASS_ICON;
        }
    }
}
