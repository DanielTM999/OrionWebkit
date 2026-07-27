package dtm.ide.navigation;

import dtm.ide.utils.WebkitPathConventions;
import dtm.stools.component.panels.editor.code.api.Location;
import dtm.stools.component.panels.editor.code.api.Range;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class JavaScriptNavigationIndex {
    private static final Set<String> IGNORED_DIRECTORIES = Set.of(
            ".git", ".idea", ".orion", "node_modules", "target",
            "build", "dist", "coverage", "vendor"
    );
    private static final int MAX_PROJECT_FILES = 25_000;

    private JavaScriptNavigationIndex() {}

    public static List<Location> findDefinitions(Path projectRoot,
                                                 Path currentFile,
                                                 String currentText,
                                                 String symbol) {
        if (!validSymbol(symbol)) {
            return List.of();
        }
        List<Pattern> patterns = declarationPatterns(symbol);
        List<Location> locations = new ArrayList<>();
        for (SourceFile source : sources(projectRoot, currentFile, currentText)) {
            String searchable = maskCommentsAndStrings(source.text());
            for (Pattern pattern : patterns) {
                Matcher matcher = pattern.matcher(searchable);
                while (matcher.find()) {
                    locations.add(location(source.path(), source.text(), matcher.start(1),
                            matcher.end(1)));
                }
            }
        }
        return distinct(locations);
    }

    public static List<Location> findReferences(Path projectRoot,
                                                Path currentFile,
                                                String currentText,
                                                String symbol) {
        if (!validSymbol(symbol)) {
            return List.of();
        }
        Pattern occurrence = Pattern.compile(
                "(?<![\\p{L}\\p{N}_$])" + Pattern.quote(symbol)
                        + "(?![\\p{L}\\p{N}_$])"
        );
        List<Location> locations = new ArrayList<>();
        for (SourceFile source : sources(projectRoot, currentFile, currentText)) {
            Matcher matcher = occurrence.matcher(maskCommentsAndStrings(source.text()));
            while (matcher.find()) {
                locations.add(location(source.path(), source.text(), matcher.start(), matcher.end()));
            }
        }
        return distinct(locations);
    }

    public static boolean isDeclarationAt(String text, String symbol, int offset) {
        if (text == null || !validSymbol(symbol) || offset < 0 || offset >= text.length()) {
            return false;
        }
        String searchable = maskCommentsAndStrings(text);
        for (Pattern pattern : declarationPatterns(symbol)) {
            Matcher matcher = pattern.matcher(searchable);
            while (matcher.find()) {
                if (matcher.start(1) == offset) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<Pattern> declarationPatterns(String symbol) {
        String name = "(" + Pattern.quote(symbol) + ")";
        return List.of(
                Pattern.compile("\\b(?:export\\s+)?(?:default\\s+)?(?:async\\s+)?function\\s+"
                        + name + "\\s*\\("),
                Pattern.compile("\\b(?:export\\s+)?(?:const|let|var)\\s+" + name
                        + "\\s*=\\s*(?:async\\s+)?(?:function\\b|\\([^)]*\\)\\s*=>"
                        + "|[\\p{L}_$][\\p{L}\\p{N}_$]*\\s*=>)"),
                Pattern.compile("\\b(?:export\\s+)?(?:default\\s+)?class\\s+" + name + "\\b"),
                Pattern.compile("(?m)^[\\t ]*(?:static\\s+)?(?:async\\s+)?(?:get\\s+|set\\s+)?"
                        + name + "\\s*\\([^;\\n{}]*\\)\\s*\\{"),
                Pattern.compile("(?m)^[\\t ]*" + name
                        + "\\s*:\\s*(?:async\\s+)?(?:function\\b|\\([^;\\n{}]*\\)\\s*=>)")
        );
    }

    private static Collection<SourceFile> sources(Path projectRoot,
                                                  Path currentFile,
                                                  String currentText) {
        LinkedHashSet<Path> paths = new LinkedHashSet<>();
        Path normalizedCurrent = normalize(currentFile);
        if (normalizedCurrent != null) {
            paths.add(normalizedCurrent);
        }
        Path root = normalize(projectRoot);
        if (root != null && Files.isDirectory(root)) {
            try (Stream<Path> stream = Files.walk(root)) {
                stream.filter(Files::isRegularFile)
                        .filter(WebkitPathConventions::isJsLike)
                        .filter(path -> !ignored(root, path))
                        .limit(MAX_PROJECT_FILES)
                        .map(JavaScriptNavigationIndex::normalize)
                        .forEach(paths::add);
            } catch (Exception ignored) {
                // The current editor contents are still searchable.
            }
        }

        List<SourceFile> sources = new ArrayList<>(paths.size());
        for (Path path : paths) {
            if (path == null) {
                continue;
            }
            if (path.equals(normalizedCurrent) && currentText != null) {
                sources.add(new SourceFile(path, currentText));
                continue;
            }
            try {
                sources.add(new SourceFile(path, Files.readString(path)));
            } catch (Exception ignored) {
                // Files that disappear or cannot be decoded are skipped.
            }
        }
        return sources;
    }

    private static boolean ignored(Path root, Path path) {
        Path normalized = normalize(path);
        if (normalized == null || !normalized.startsWith(root)) {
            return false;
        }
        for (Path segment : root.relativize(normalized)) {
            if (IGNORED_DIRECTORIES.contains(segment.toString().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static Location location(Path file, String text, int startOffset, int endOffset) {
        int[] start = lineAndColumn(text, startOffset);
        int[] end = lineAndColumn(text, endOffset);
        return Location.of(file.toUri().toString(),
                Range.of(start[0], start[1], end[0], end[1]));
    }

    private static int[] lineAndColumn(String text, int offset) {
        int safeOffset = Math.max(0, Math.min(offset, text.length()));
        int line = 0;
        int lineStart = 0;
        for (int index = 0; index < safeOffset; index++) {
            if (text.charAt(index) == '\n') {
                line++;
                lineStart = index + 1;
            }
        }
        return new int[]{line, safeOffset - lineStart};
    }

    private static List<Location> distinct(List<Location> locations) {
        List<Location> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Location location : locations) {
            String key = location.uri() + ':' + location.range().start().line()
                    + ':' + location.range().start().col();
            if (seen.add(key)) {
                result.add(location);
            }
        }
        return List.copyOf(result);
    }

    private static boolean validSymbol(String symbol) {
        return symbol != null && symbol.matches("[\\p{L}_$][\\p{L}\\p{N}_$]*");
    }

    private static Path normalize(Path path) {
        return path == null ? null : path.toAbsolutePath().normalize();
    }

    private static String maskCommentsAndStrings(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder masked = new StringBuilder(text);
        State state = State.CODE;
        boolean escaped = false;
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            char next = index + 1 < text.length() ? text.charAt(index + 1) : '\0';
            switch (state) {
                case CODE -> {
                    if (current == '/' && next == '/') {
                        masked.setCharAt(index, ' ');
                        state = State.LINE_COMMENT;
                    } else if (current == '/' && next == '*') {
                        masked.setCharAt(index, ' ');
                        state = State.BLOCK_COMMENT;
                    } else if (current == '\'') {
                        masked.setCharAt(index, ' ');
                        state = State.SINGLE_QUOTE;
                    } else if (current == '"') {
                        masked.setCharAt(index, ' ');
                        state = State.DOUBLE_QUOTE;
                    } else if (current == '`') {
                        masked.setCharAt(index, ' ');
                        state = State.TEMPLATE;
                    }
                }
                case LINE_COMMENT -> {
                    if (current == '\n') {
                        state = State.CODE;
                    } else {
                        masked.setCharAt(index, ' ');
                    }
                }
                case BLOCK_COMMENT -> {
                    if (current == '*' && next == '/') {
                        masked.setCharAt(index, ' ');
                        if (index + 1 < masked.length()) {
                            masked.setCharAt(++index, ' ');
                        }
                        state = State.CODE;
                    } else if (current != '\n' && current != '\r') {
                        masked.setCharAt(index, ' ');
                    }
                }
                case SINGLE_QUOTE, DOUBLE_QUOTE, TEMPLATE -> {
                    char delimiter = state == State.SINGLE_QUOTE ? '\''
                            : state == State.DOUBLE_QUOTE ? '"' : '`';
                    if (current != '\n' && current != '\r') {
                        masked.setCharAt(index, ' ');
                    }
                    if (!escaped && current == delimiter) {
                        state = State.CODE;
                    }
                    escaped = !escaped && current == '\\';
                    if (current != '\\') {
                        escaped = false;
                    }
                }
            }
        }
        return masked.toString();
    }

    private record SourceFile(Path path, String text) {}

    private enum State {
        CODE,
        LINE_COMMENT,
        BLOCK_COMMENT,
        SINGLE_QUOTE,
        DOUBLE_QUOTE,
        TEMPLATE
    }
}
