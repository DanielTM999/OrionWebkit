package dtm.ide.lsp;

import dtm.ide.api.project.editor.FormatCodeContext;
import dtm.ide.api.project.editor.IdeFormatScope;
import dtm.stools.component.panels.editor.code.api.Position;
import dtm.stools.component.panels.editor.code.api.Range;
import dtm.stools.component.panels.editor.code.api.TextEdit;
import java.util.*;

public final class WebkitFormatting {
    private WebkitFormatting() { }

    public record Document(String text, int start, int end, boolean selection) {
        public Range range() { return Range.of(position(text, start), position(text, end)); }
        public String source() { return text.substring(start, end); }
    }

    public static Document document(FormatCodeContext context) {
        boolean selection = context.formatScope() == IdeFormatScope.SELECTION;
        String source = context.chainedText() != null ? context.chainedText() : context.text();
        String full = context.fullText() == null ? source : context.fullText();
        if (full == null) full = "";
        if (source == null) source = "";
        int start = selection ? Math.clamp(context.startOffset(), 0, full.length()) : 0;
        int end = selection ? Math.clamp(context.endOffset(), start, full.length()) : full.length();
        if (context.fullText() == null && selection) { start = 0; end = full.length(); }
        if (selection || context.chainedText() != null) {
            full = full.substring(0, start) + source + full.substring(end);
            end = start + source.length();
        }
        return new Document(full, start, end, selection);
    }

    public static Map<String, Object> options(FormatCodeContext context, String text) {
        int indent = context.indentSize() > 0 ? context.indentSize() : Math.max(1, context.tabSize());
        String newline = text.contains("\r\n") ? "\r\n" : text.contains("\r") ? "\r" : "\n";
        return Map.of("tabSize", context.useSpacesForTab() ? indent : Math.max(1, context.tabSize()),
                "indentSize", indent, "insertSpaces", context.useSpacesForTab(), "newLineCharacter", newline);
    }

    public static String apply(Document document, List<TextEdit> edits) {
        record Edit(int start, int end, String text) { }
        List<Edit> changes = new ArrayList<>();
        for (TextEdit edit : edits) {
            int start = offset(document.text(), edit.range().start());
            int end = offset(document.text(), edit.range().end());
            if (end < start) throw new IllegalArgumentException("Invalid formatting range");
            String replacement = edit.newText();
            if (replacement == null) replacement = "";
            String newline = document.text().contains("\r\n") ? "\r\n" : document.text().contains("\r") ? "\r" : "\n";
            replacement = replacement.replace("\r\n", "\n").replace('\r', '\n').replace("\n", newline);
            if (document.selection()) {
                int prefix = 0;
                while (prefix < replacement.length() && start + prefix < end
                        && document.text().charAt(start + prefix) == replacement.charAt(prefix)) prefix++;
                start += prefix;
                replacement = replacement.substring(prefix);
                int suffix = 0;
                while (suffix < replacement.length() && end - suffix > start
                        && document.text().charAt(end - suffix - 1) == replacement.charAt(replacement.length() - suffix - 1)) suffix++;
                end -= suffix;
                replacement = replacement.substring(0, replacement.length() - suffix);
                if (start < document.start() || end > document.end()) continue;
            }
            changes.add(new Edit(start, end, replacement));
        }
        changes.sort(Comparator.comparingInt(Edit::start).reversed());
        int previousStart = document.end();
        StringBuilder result = new StringBuilder(document.source());
        for (Edit edit : changes) {
            if (edit.end() > previousStart) throw new IllegalArgumentException("Overlapping formatting edits");
            result.replace(edit.start() - document.start(), edit.end() - document.start(), edit.text());
            previousStart = edit.start();
        }
        return result.toString();
    }

    public static Position position(String text, int offset) {
        if (offset < 0 || offset > text.length()) throw new IllegalArgumentException("Invalid offset");
        int line = 0;
        int column = 0;
        for (int i = 0; i < offset; i++) {
            char c = text.charAt(i);
            if (c == '\r') {
                if (i + 1 < offset && text.charAt(i + 1) == '\n') i++;
                line++;
                column = 0;
            } else if (c == '\n') { line++; column = 0; }
            else column++;
        }
        return Position.of(line, column);
    }

    private static int offset(String text, Position position) {
        int line = 0;
        int start = 0;
        while (line < position.line() && start < text.length()) {
            char c = text.charAt(start++);
            if (c == '\r') {
                if (start < text.length() && text.charAt(start) == '\n') start++;
                line++;
            } else if (c == '\n') line++;
        }
        int end = start;
        while (end < text.length() && text.charAt(end) != '\r' && text.charAt(end) != '\n') end++;
        if (line != position.line() || position.col() < 0 || start + position.col() > end) throw new IllegalArgumentException("Invalid formatting position");
        return start + position.col();
    }
}
