package dtm.ide.editor;

import dtm.ide.utils.WebkitPathConventions;
import dtm.stools.component.panels.editor.code.autocomplete.AutoCompleteItem;
import dtm.stools.component.panels.editor.code.api.Range;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

public final class BootstrapCompletionProvider {
    private static final List<String> CLASSES = load("classes.txt");
    private static final List<String> VARIABLES = load("variables.txt");
    private static final List<String> ATTRIBUTES = List.of("data-bs-toggle", "data-bs-target", "data-bs-dismiss", "data-bs-theme", "data-bs-parent", "data-bs-backdrop", "data-bs-keyboard", "data-bs-placement", "data-bs-title", "data-bs-content", "data-bs-trigger", "data-bs-animation", "data-bs-delay", "data-bs-html", "data-bs-offset", "data-bs-ride", "data-bs-interval", "data-bs-slide", "data-bs-slide-to", "data-bs-scroll", "data-bs-spy", "data-bs-root-margin", "data-bs-smooth-scroll", "data-bs-auto-close");
    private static final Map<String, List<String>> VALUES = Map.of(
            "data-bs-toggle", List.of("modal", "collapse", "dropdown", "tab", "pill", "tooltip", "popover", "offcanvas", "button"),
            "data-bs-dismiss", List.of("alert", "modal", "offcanvas", "toast"),
            "data-bs-theme", List.of("light", "dark"),
            "data-bs-placement", List.of("top", "bottom", "left", "right", "auto"),
            "data-bs-backdrop", List.of("true", "false", "static"),
            "data-bs-keyboard", List.of("true", "false"),
            "data-bs-ride", List.of("carousel"),
            "data-bs-slide", List.of("prev", "next"),
            "data-bs-auto-close", List.of("true", "false", "inside", "outside"));
    private static final Pattern VALUE = Pattern.compile("(?:^|\\s)(class|className|data-bs-[\\w-]+)\\s*=\\s*(?:\\{\\s*)?([\"'`])([^\"'`]*)$", Pattern.DOTALL);
    private static final Pattern TAG = Pattern.compile("</?[A-Za-z][\\w.:-]*(?:\\s.*)?", Pattern.DOTALL);

    public record Completion(List<AutoCompleteItem> items, boolean exclusive) { }
    private record Candidates(List<String> values, String prefix, int start, boolean attribute, Set<String> used) { }

    private static List<String> load(String name) {
        try (InputStream stream = BootstrapCompletionProvider.class.getResourceAsStream("/bootstrap/" + name)) {
            if (stream == null) throw new IllegalStateException("Missing Bootstrap catalog: " + name);
            return new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).lines().filter(line -> !line.isBlank()).toList();
        } catch (IOException error) { throw new UncheckedIOException(error); }
    }

    public Completion complete(Path file, String text, int offset) {
        if (text == null || file == null || offset < 0 || offset > text.length()) return new Completion(List.of(), false);
        Candidates candidates = candidates(file, text, offset);
        if (candidates == null) return new Completion(List.of(), false);
        int end = offset;
        while (end < text.length() && token(text.charAt(end))) end++;
        Range range = range(text, candidates.start(), end);
        List<AutoCompleteItem> result = candidates.values().stream()
                .filter(value -> value.startsWith(candidates.prefix()) && !candidates.used().contains(value))
                .limit(150)
                .map(value -> new AutoCompleteItem(candidates.attribute() ? value + "=\"\"" : value,
                        value, "Bootstrap 5.3", "Bootstrap completion", null, AutoCompleteItem.Kind.TEXT,
                        List.of(), false, null, range)).toList();
        return new Completion(result, !candidates.attribute());
    }

    private Candidates candidates(Path file, String text, int offset) {
        String beforeCaret = text.substring(0, offset);
        if (beforeCaret.lastIndexOf("<!--") > beforeCaret.lastIndexOf("-->")) return null;
        if (beforeCaret.lastIndexOf("/*") > beforeCaret.lastIndexOf("*/")) return null;
        int start = offset;
        while (start > 0 && token(text.charAt(start - 1))) start--;
        String prefix = text.substring(start, offset);
        boolean css = WebkitPathConventions.isCssLike(file);
        if (!css && WebkitPathConventions.isHtmlLike(file)) {
            String before = text.substring(0, offset).toLowerCase(Locale.ROOT);
            css = before.lastIndexOf("<style") > before.lastIndexOf("</style");
            if (before.lastIndexOf("<script") > before.lastIndexOf("</script")) return null;
        }
        if (css) {
            if (prefix.startsWith("--bs-")) return new Candidates(VARIABLES, prefix, start, false, Set.of());
            int open = text.lastIndexOf('{', Math.max(0, offset - 1));
            int close = text.lastIndexOf('}', Math.max(0, offset - 1));
            if (start > 0 && text.charAt(start - 1) == '.' && open <= close) return new Candidates(CLASSES, prefix, start, false, Set.of());
            return null;
        }
        int open = text.lastIndexOf('<', Math.max(0, offset - 1));
        if (open < 0) return null;
        String tag = text.substring(open, offset);
        if (!TAG.matcher(tag).matches() || outsideQuoteContainsClose(tag)) return null;
        var value = VALUE.matcher(tag);
        if (value.find()) {
            String content = value.group(3);
            if (content.contains("${")) return null;
            String attribute = value.group(1);
            if (attribute.equals("class") || attribute.equals("className")) {
                int valueStart = open + value.start(3);
                if (start < valueStart) start = valueStart;
                Set<String> used = new HashSet<>(List.of(text.substring(valueStart, start).trim().split("\\s+")));
                return new Candidates(CLASSES, text.substring(start, offset), start, false, used);
            }
            return new Candidates(VALUES.getOrDefault(attribute, List.of()), prefix, start, false, Set.of());
        }
        if (unclosedQuote(tag) || !prefix.startsWith("data-")) return null;
        return new Candidates(ATTRIBUTES, prefix, start, true, Set.of());
    }

    private static boolean token(char c) { return Character.isLetterOrDigit(c) || c == '-' || c == '_'; }

    private static boolean outsideQuoteContainsClose(String text) {
        char quote = 0;
        int braces = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) { if (c == quote && (i == 0 || text.charAt(i - 1) != '\\')) quote = 0; }
            else if (c == '\'' || c == '"' || c == '`') quote = c;
            else if (c == '{') braces++;
            else if (c == '}') braces = Math.max(0, braces - 1);
            else if (c == '>' && braces == 0) return true;
        }
        return false;
    }

    private static boolean unclosedQuote(String text) {
        char quote = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote == 0 && (c == '\'' || c == '"' || c == '`')) quote = c;
            else if (c == quote && (i == 0 || text.charAt(i - 1) != '\\')) quote = 0;
        }
        return quote != 0;
    }

    private static Range range(String text, int start, int end) {
        int line = 0;
        int column = 0;
        int startLine = 0;
        int startColumn = 0;
        for (int i = 0; i <= end; i++) {
            if (i == start) { startLine = line; startColumn = column; }
            if (i == end) return Range.of(startLine, startColumn, line, column);
            if (text.charAt(i) == '\n') { line++; column = 0; } else column++;
        }
        throw new IllegalArgumentException();
    }
}
