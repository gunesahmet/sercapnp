package com.sercapnp.lang;

import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.ExternalAnnotator;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Background annotator for Cap'n Proto schema files.
 *
 * Checks performed:
 *   1. Naming: field/enumerant/method names must start with lowercase
 *   2. Naming: struct/enum/interface names must start with uppercase
 *   3. Duplicate ordinals within the same struct scope
 *   4. Ordinal gaps within the same struct scope
 *   5. Import validation: unresolvable import paths
 *   6. Unused imports: imported files never referenced
 */
public class CapnpAnnotator extends ExternalAnnotator<CapnpAnnotator.FileInfo, List<CapnpAnnotator.Issue>> {

    // ── Patterns ────────────────────────────────────────────────────────────────

    // struct/enum/interface Name
    private static final Pattern TYPE_DEF = Pattern.compile(
            "^(\\s*)(struct|enum|interface)\\s+([A-Za-z][A-Za-z0-9]*)\\b",
            Pattern.MULTILINE
    );

    // fieldName @N :Type;  (inside a block — indented)
    private static final Pattern FIELD_DECL = Pattern.compile(
            "^(\\s+)([A-Za-z][A-Za-z0-9]*)\\s+@(\\d+)\\s*:",
            Pattern.MULTILINE
    );

    // Enumerant: name @N (inside enum — no colon after ordinal)
    private static final Pattern ENUMERANT_DECL = Pattern.compile(
            "^(\\s+)([A-Za-z][A-Za-z0-9]*)\\s+@(\\d+)\\s*(?:;|\\$)",
            Pattern.MULTILINE
    );

    // Method: name @N (params) -> (results);
    private static final Pattern METHOD_DECL = Pattern.compile(
            "^(\\s+)([A-Za-z][A-Za-z0-9]*)\\s+@(\\d+)\\s*(?:\\[|\\()",
            Pattern.MULTILINE
    );

    // const name :Type
    private static final Pattern CONST_DECL = Pattern.compile(
            "^(\\s*)const\\s+([A-Za-z][A-Za-z0-9]*)\\s*:",
            Pattern.MULTILINE
    );

    // import "path"
    private static final Pattern IMPORT_DECL = Pattern.compile(
            "^(\\s*)(?:using\\s+(?:[A-Za-z][A-Za-z0-9]*\\s*=\\s*)?)?import\\s+\"([^\"]+)\"",
            Pattern.MULTILINE
    );

    // Any @N ordinal (for scope-based analysis)
    private static final Pattern ORDINAL = Pattern.compile("@(\\d+)");

    // Block openers with keyword
    private static final Pattern BLOCK_OPEN = Pattern.compile(
            "(struct|enum|interface|union|group)\\s+[A-Za-z][A-Za-z0-9]*\\s*(?:\\([^)]*\\))?\\s*(?:@0x[a-fA-F0-9]+)?\\s*\\{" +
            "|\\bunion\\s*\\{|\\bgroup\\s*\\{"
    );

    // ── Data classes ────────────────────────────────────────────────────────────

    static class FileInfo {
        final String text;
        final String fileName;
        final VirtualFile virtualFile;
        final Project project;

        FileInfo(String text, String fileName, VirtualFile virtualFile, Project project) {
            this.text = text;
            this.fileName = fileName;
            this.virtualFile = virtualFile;
            this.project = project;
        }
    }

    static class Issue {
        final int offset;
        final int length;
        final String message;
        final HighlightSeverity severity;

        Issue(int offset, int length, String message, HighlightSeverity severity) {
            this.offset = offset;
            this.length = length;
            this.message = message;
            this.severity = severity;
        }
    }

    // ── ExternalAnnotator lifecycle ─────────────────────────────────────────────

    @Nullable
    @Override
    public FileInfo collectInformation(@NotNull PsiFile file) {
        if (!(file instanceof CapnpFile)) return null;

        VirtualFile vFile = file.getVirtualFile();
        if (vFile == null && file.getOriginalFile() != null) {
            vFile = file.getOriginalFile().getVirtualFile();
        }

        return new FileInfo(
                file.getText(),
                vFile != null ? vFile.getName() : "unknown.capnp",
                vFile,
                file.getProject()
        );
    }

    @Nullable
    @Override
    public List<Issue> doAnnotate(FileInfo info) {
        if (info == null) return null;

        List<Issue> issues = new ArrayList<>();
        String text = info.text;
        String stripped = stripComments(text);

        // 1. Naming checks
        checkTypeNaming(stripped, text, issues);
        checkFieldNaming(stripped, text, issues);
        checkEnumerantNaming(stripped, text, issues);
        checkMethodNaming(stripped, text, issues);
        checkConstNaming(stripped, text, issues);

        // 2-3. Duplicate ordinals & ordinal gaps (per-scope)
        checkOrdinalsInScopes(text, issues);

        // 4. Import validation
        checkImports(stripped, text, info, issues);

        // 5. Unused imports
        checkUnusedImports(stripped, text, info, issues);

        return issues;
    }

    @Override
    public void apply(@NotNull PsiFile file, List<Issue> issues, @NotNull AnnotationHolder holder) {
        if (issues == null || issues.isEmpty()) return;

        String text = file.getText();
        int textLength = text.length();

        for (Issue issue : issues) {
            if (issue.offset < 0 || issue.offset >= textLength) continue;
            int end = Math.min(issue.offset + issue.length, textLength);
            if (end <= issue.offset) continue;

            holder.newAnnotation(issue.severity, issue.message)
                    .range(new com.intellij.openapi.util.TextRange(issue.offset, end))
                    .create();
        }
    }

    // ── Check implementations ───────────────────────────────────────────────────

    /**
     * Check 1a: struct/enum/interface names must start with uppercase.
     */
    private void checkTypeNaming(String stripped, String original, List<Issue> issues) {
        Matcher m = TYPE_DEF.matcher(stripped);
        while (m.find()) {
            String name = m.group(3);
            if (Character.isLowerCase(name.charAt(0))) {
                int nameOffset = findInOriginal(original, m.start(), name);
                if (nameOffset >= 0) {
                    issues.add(new Issue(nameOffset, name.length(),
                            "Type name '" + name + "' must start with an uppercase letter",
                            HighlightSeverity.ERROR));
                }
            }
        }
    }

    /**
     * Check 1b: field names must start with lowercase.
     */
    private void checkFieldNaming(String stripped, String original, List<Issue> issues) {
        Matcher m = FIELD_DECL.matcher(stripped);
        while (m.find()) {
            String name = m.group(2);
            if (Character.isUpperCase(name.charAt(0))) {
                int nameOffset = findInOriginal(original, m.start(), name);
                if (nameOffset >= 0) {
                    issues.add(new Issue(nameOffset, name.length(),
                            "Field name '" + name + "' must start with a lowercase letter",
                            HighlightSeverity.ERROR));
                }
            }
        }
    }

    /**
     * Check 1c: enumerant names must start with lowercase.
     */
    private void checkEnumerantNaming(String stripped, String original, List<Issue> issues) {
        Matcher m = ENUMERANT_DECL.matcher(stripped);
        while (m.find()) {
            String name = m.group(2);
            if (Character.isUpperCase(name.charAt(0))) {
                int nameOffset = findInOriginal(original, m.start(), name);
                if (nameOffset >= 0) {
                    issues.add(new Issue(nameOffset, name.length(),
                            "Enumerant name '" + name + "' must start with a lowercase letter",
                            HighlightSeverity.ERROR));
                }
            }
        }
    }

    /**
     * Check 1d: method names must start with lowercase.
     */
    private void checkMethodNaming(String stripped, String original, List<Issue> issues) {
        Matcher m = METHOD_DECL.matcher(stripped);
        while (m.find()) {
            String name = m.group(2);
            // Skip keywords that look like methods
            if (isKeyword(name)) continue;
            if (Character.isUpperCase(name.charAt(0))) {
                int nameOffset = findInOriginal(original, m.start(), name);
                if (nameOffset >= 0) {
                    issues.add(new Issue(nameOffset, name.length(),
                            "Method name '" + name + "' must start with a lowercase letter",
                            HighlightSeverity.ERROR));
                }
            }
        }
    }

    /**
     * Check 1e: const names must start with lowercase.
     */
    private void checkConstNaming(String stripped, String original, List<Issue> issues) {
        Matcher m = CONST_DECL.matcher(stripped);
        while (m.find()) {
            String name = m.group(2);
            if (Character.isUpperCase(name.charAt(0))) {
                int nameOffset = findInOriginal(original, m.start(), name);
                if (nameOffset >= 0) {
                    issues.add(new Issue(nameOffset, name.length(),
                            "Const name '" + name + "' must start with a lowercase letter",
                            HighlightSeverity.ERROR));
                }
            }
        }
    }

    /**
     * Check 2 & 3: duplicate ordinals and ordinal gaps.
     * Analyzes each struct scope independently.
     */
    private void checkOrdinalsInScopes(String text, List<Issue> issues) {
        // Find all struct/enum/interface definitions and check ordinals within each
        List<ScopeInfo> scopes = findScopes(text);

        for (ScopeInfo scope : scopes) {
            List<OrdinalInfo> ordinals = collectOrdinalsInBlock(
                    text, scope.bodyStart, scope.bodyEnd, scope.kind);

            if (ordinals.isEmpty()) continue;

            // Check duplicates
            Map<Integer, OrdinalInfo> seen = new HashMap<>();
            for (OrdinalInfo ord : ordinals) {
                OrdinalInfo existing = seen.get(ord.value);
                if (existing != null) {
                    issues.add(new Issue(ord.offset, ord.length,
                            "Duplicate ordinal @" + ord.value + " (first used at line " +
                            getLineNumber(text, existing.offset) + ")",
                            HighlightSeverity.ERROR));
                } else {
                    seen.put(ord.value, ord);
                }
            }

            // Check gaps (only if no duplicates — otherwise gaps are expected)
            if (seen.size() == ordinals.size() && ordinals.size() >= 2) {
                TreeSet<Integer> values = new TreeSet<>(seen.keySet());
                int min = values.first();
                int max = values.last();
                List<Integer> missing = new ArrayList<>();

                for (int i = min; i <= max; i++) {
                    if (!values.contains(i)) {
                        missing.add(i);
                    }
                }

                if (!missing.isEmpty() && missing.size() <= 10) {
                    // Report gap on the scope's opening keyword
                    String gapStr = formatGapList(missing);
                    issues.add(new Issue(scope.keywordOffset, scope.keywordLength,
                            "Ordinal gap in " + scope.kind + " '" + scope.name +
                            "': missing " + gapStr,
                            HighlightSeverity.WARNING));
                }
            }
        }
    }

    /**
     * Check 4: import paths that don't resolve.
     */
    private void checkImports(String stripped, String original, FileInfo info, List<Issue> issues) {
        if (info.project == null || info.project.isDisposed()) return;

        Matcher m = IMPORT_DECL.matcher(stripped);
        while (m.find()) {
            String path = m.group(2);

            // Skip standard library imports
            if (path.startsWith("/capnp/")) continue;

            VirtualFile resolved = CapnpSchemaScanner.resolveImport(
                    info.virtualFile, path, info.project);

            if (resolved == null) {
                // Find the path string in the original text
                int pathOffset = original.indexOf("\"" + path + "\"", m.start());
                if (pathOffset >= 0) {
                    pathOffset++; // Skip the opening quote
                    issues.add(new Issue(pathOffset, path.length(),
                            "Cannot resolve import: \"" + path + "\"",
                            HighlightSeverity.ERROR));
                }
            }
        }
    }

    /**
     * Check 5: unused imports.
     */
    private void checkUnusedImports(String stripped, String original, FileInfo info, List<Issue> issues) {
        // Find all imports with their using aliases
        Map<String, ImportEntry> imports = new LinkedHashMap<>();

        // using Alias = import "path"
        Pattern usingImport = Pattern.compile(
                "^\\s*using\\s+([A-Z][A-Za-z0-9]*)\\s*=\\s*import\\s+\"([^\"]+)\"",
                Pattern.MULTILINE
        );
        Matcher m = usingImport.matcher(stripped);
        while (m.find()) {
            imports.put(m.group(1), new ImportEntry(m.group(1), m.group(2), m.start()));
        }

        // using import "path".Type (type name becomes the alias)
        Pattern usingImportDirect = Pattern.compile(
                "^\\s*using\\s+import\\s+\"([^\"]+)\"\\s*\\.\\s*([A-Z][A-Za-z0-9]*)",
                Pattern.MULTILINE
        );
        m = usingImportDirect.matcher(stripped);
        while (m.find()) {
            imports.put(m.group(2), new ImportEntry(m.group(2), m.group(1), m.start()));
        }

        if (imports.isEmpty()) return;

        // Check which aliases are actually used in the rest of the file
        for (Map.Entry<String, ImportEntry> entry : imports.entrySet()) {
            String alias = entry.getKey();
            ImportEntry imp = entry.getValue();

            // Count references to this alias (excluding the import line itself)
            // Look for alias. or :alias or (alias) patterns
            Pattern usagePattern = Pattern.compile(
                    "\\b" + Pattern.quote(alias) + "\\b"
            );
            Matcher usageMatcher = usagePattern.matcher(stripped);

            int usageCount = 0;
            while (usageMatcher.find()) {
                // Skip the import/using line itself
                if (usageMatcher.start() >= imp.offset &&
                        usageMatcher.start() < imp.offset + 200) {
                    // Likely the import line — check if on the same line
                    int lineStart = stripped.lastIndexOf('\n', usageMatcher.start()) + 1;
                    int impLineStart = stripped.lastIndexOf('\n', imp.offset) + 1;
                    if (lineStart == impLineStart) continue;
                }
                usageCount++;
            }

            if (usageCount == 0) {
                // Find the using/import keyword in the original text for highlighting
                int lineStart = original.lastIndexOf('\n', imp.offset) + 1;
                int lineEnd = original.indexOf('\n', imp.offset);
                if (lineEnd < 0) lineEnd = original.length();
                String line = original.substring(lineStart, lineEnd);

                issues.add(new Issue(lineStart, lineEnd - lineStart,
                        "Unused import: '" + alias + "' from \"" + imp.path + "\"",
                        HighlightSeverity.WARNING));
            }
        }
    }

    // ── Scope analysis helpers ──────────────────────────────────────────────────

    private static class ScopeInfo {
        final String kind;      // "struct", "enum", "interface"
        final String name;
        final int keywordOffset;
        final int keywordLength;
        final int bodyStart;    // Position of '{'
        final int bodyEnd;      // Position of '}'

        ScopeInfo(String kind, String name, int keywordOffset, int keywordLength,
                  int bodyStart, int bodyEnd) {
            this.kind = kind;
            this.name = name;
            this.keywordOffset = keywordOffset;
            this.keywordLength = keywordLength;
            this.bodyStart = bodyStart;
            this.bodyEnd = bodyEnd;
        }
    }

    private static class OrdinalInfo {
        final int value;
        final int offset; // Position of '@' in text
        final int length; // Length of '@N'

        OrdinalInfo(int value, int offset, int length) {
            this.value = value;
            this.offset = offset;
            this.length = length;
        }
    }

    private static class ImportEntry {
        final String alias;
        final String path;
        final int offset;

        ImportEntry(String alias, String path, int offset) {
            this.alias = alias;
            this.path = path;
            this.offset = offset;
        }
    }

    /**
     * Find all struct/enum/interface scopes in the file.
     */
    private List<ScopeInfo> findScopes(String text) {
        List<ScopeInfo> scopes = new ArrayList<>();

        Pattern scopePattern = Pattern.compile(
                "\\b(struct|enum|interface)\\s+([A-Za-z][A-Za-z0-9]*)\\s*" +
                "(?:\\([^)]*\\))?\\s*(?:@0x[a-fA-F0-9]+)?\\s*(?:\\$[^;{]*)?\\s*\\{"
        );

        Matcher m = scopePattern.matcher(text);
        while (m.find()) {
            if (isInStringOrComment(text, 0, m.start())) continue;

            String kind = m.group(1);
            String name = m.group(2);
            int bracePos = text.indexOf('{', m.start());
            if (bracePos < 0) continue;

            int closePos = findMatchingClose(text, bracePos);
            if (closePos < 0) continue;

            int keywordStart = m.start();
            scopes.add(new ScopeInfo(kind, name, keywordStart, kind.length(),
                    bracePos, closePos));
        }

        return scopes;
    }

    /**
     * Collect ordinals within a block.
     * For struct: includes union/group ordinals (shared space), skips nested struct/enum/interface.
     * For enum: simple — all ordinals are enumerants.
     */
    private List<OrdinalInfo> collectOrdinalsInBlock(String text, int start, int end, String kind) {
        List<OrdinalInfo> ordinals = new ArrayList<>();

        if ("enum".equals(kind)) {
            // Enum: simple scan, no nesting concerns
            Matcher m = ORDINAL.matcher(text);
            int searchFrom = start + 1;
            while (m.find(searchFrom)) {
                if (m.start() >= end) break;
                // Skip hex IDs
                if (m.end() < text.length() &&
                        (text.charAt(m.end()) == 'x' || text.charAt(m.end()) == 'X')) {
                    searchFrom = m.end();
                    continue;
                }
                ordinals.add(new OrdinalInfo(
                        Integer.parseInt(m.group(1)), m.start(), m.end() - m.start()));
                searchFrom = m.end();
            }
        } else {
            // Struct/interface: skip nested struct/enum/interface bodies
            List<int[]> skipRanges = findNestedIndependentBlocks(text, start, end);

            Matcher m = ORDINAL.matcher(text);
            int searchFrom = start + 1;
            while (m.find(searchFrom)) {
                if (m.start() >= end) break;

                // Skip hex IDs
                if (m.end() < text.length() &&
                        (text.charAt(m.end()) == 'x' || text.charAt(m.end()) == 'X')) {
                    searchFrom = m.end();
                    continue;
                }

                // Skip nested independent blocks
                boolean skip = false;
                for (int[] range : skipRanges) {
                    if (m.start() > range[0] && m.start() < range[1]) {
                        skip = true;
                        break;
                    }
                }

                if (!skip) {
                    ordinals.add(new OrdinalInfo(
                            Integer.parseInt(m.group(1)), m.start(), m.end() - m.start()));
                }
                searchFrom = m.end();
            }
        }

        return ordinals;
    }

    private List<int[]> findNestedIndependentBlocks(String text, int scopeStart, int scopeEnd) {
        List<int[]> ranges = new ArrayList<>();
        Pattern nestedDef = Pattern.compile(
                "\\b(struct|enum|interface)\\s+[A-Z][A-Za-z0-9]*\\s*(?:\\([^)]*\\))?\\s*(?:@0x[a-fA-F0-9]+)?\\s*\\{"
        );
        Matcher m = nestedDef.matcher(text);
        int searchFrom = scopeStart + 1;

        while (m.find(searchFrom)) {
            if (m.start() >= scopeEnd) break;
            if (isInStringOrComment(text, scopeStart, m.start())) {
                searchFrom = m.end();
                continue;
            }
            int bracePos = text.indexOf('{', m.start());
            if (bracePos < 0 || bracePos >= scopeEnd) { searchFrom = m.end(); continue; }
            int closePos = findMatchingClose(text, bracePos);
            if (closePos > 0 && closePos <= scopeEnd) {
                ranges.add(new int[]{bracePos, closePos});
                searchFrom = closePos + 1;
            } else {
                searchFrom = m.end();
            }
        }
        return ranges;
    }

    // ── Utility methods ─────────────────────────────────────────────────────────

    private int findMatchingClose(String text, int openPos) {
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

    private boolean isInStringOrComment(String text, int from, int pos) {
        boolean inString = false;
        boolean inComment = false;
        for (int i = from; i < pos && i < text.length(); i++) {
            char c = text.charAt(i);
            if (inComment) { if (c == '\n') inComment = false; continue; }
            if (inString) {
                if (c == '\\') { i++; continue; }
                if (c == '"') inString = false;
                continue;
            }
            if (c == '#') inComment = true;
            else if (c == '"') inString = true;
        }
        return inString || inComment;
    }

    private String stripComments(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        boolean inString = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                sb.append(c);
                if (c == '\\' && i + 1 < text.length()) sb.append(text.charAt(++i));
                else if (c == '"') inString = false;
                continue;
            }
            if (c == '"') { inString = true; sb.append(c); }
            else if (c == '#') {
                // Replace comment with spaces to preserve offsets
                while (i < text.length() && text.charAt(i) != '\n') {
                    sb.append(' ');
                    i++;
                }
                if (i < text.length()) sb.append('\n');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Find the offset of 'name' in the original text, searching from a hint position.
     * Used to map from stripped-text positions to original-text positions.
     */
    private int findInOriginal(String original, int hintOffset, String name) {
        // The offset in stripped text preserves positions (comments replaced with spaces)
        // So we can search from hintOffset directly
        int idx = original.indexOf(name, Math.max(0, hintOffset - 5));
        if (idx >= 0 && idx <= hintOffset + name.length() + 50) {
            return idx;
        }
        return hintOffset; // fallback
    }

    private int getLineNumber(String text, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < text.length(); i++) {
            if (text.charAt(i) == '\n') line++;
        }
        return line;
    }

    private String formatGapList(List<Integer> missing) {
        if (missing.size() <= 5) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < missing.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append("@").append(missing.get(i));
            }
            return sb.toString();
        }
        // Summarize: @5, @6, @7 ... @12 (8 missing)
        return "@" + missing.get(0) + " ... @" + missing.get(missing.size() - 1) +
                " (" + missing.size() + " missing)";
    }

    private boolean isKeyword(String name) {
        switch (name) {
            case "struct": case "enum": case "interface": case "union":
            case "group": case "import": case "using": case "const":
            case "annotation": case "extends":
                return true;
            default:
                return false;
        }
    }
}
