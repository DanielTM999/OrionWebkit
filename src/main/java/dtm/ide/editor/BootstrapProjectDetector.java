package dtm.ide.editor;

import dtm.ide.utils.WebkitPathConventions;
import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.Pattern;

public final class BootstrapProjectDetector {
    private static final Set<String> EXCLUDED = Set.of("node_modules", ".git", ".orion", "dist", "build", "target", ".next");
    private static final Pattern COMMENTS = Pattern.compile("<!--.*?(?:-->|$)|/\\*.*?(?:\\*/|$)|(?m)^\\s*//[^\\r\\n]*", Pattern.DOTALL);
    private static final Pattern REFERENCES = Pattern.compile(
            "<(?:link|script)\\b[^>]*\\b(?:href|src)\\s*=\\s*[\"'][^\"']*bootstrap(?:[.@/\\w-]*)(?:\\.css|\\.js)[^\"']*[\"']"
            + "|\\b(?:import|from|require\\s*\\()\\s*[\"'](?:~?bootstrap(?:/[^\"']*)?|react-bootstrap(?:/[^\"']*)?)[\"']"
            + "|\\b(?:import|from|require\\s*\\()\\s*[\"'][^\"']*(?:/|^)bootstrap(?:\\.min)?\\.(?:css|js)[\"']"
            + "|@(?:import|use|forward)\\s+(?:url\\(\\s*)?[\"'][^\"']*bootstrap[^\"']*[\"']",
            Pattern.CASE_INSENSITIVE);
    private static final JsonMapper JSON = new JsonMapper();

    public static boolean hasReference(String text) {
        return text != null && REFERENCES.matcher(COMMENTS.matcher(text).replaceAll("")).find();
    }

    public static Set<Path> find(Path project) throws IOException {
        Path root = project.toAbsolutePath().normalize();
        Set<Path> found = new HashSet<>();
        Files.walkFileTree(root, Set.of(), 24, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) {
                if (Thread.currentThread().isInterrupted()) return FileVisitResult.TERMINATE;
                return EXCLUDED.contains(directory.getFileName().toString()) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (Thread.currentThread().isInterrupted()) return FileVisitResult.TERMINATE;
                if (attrs.size() > 1_000_000) return FileVisitResult.CONTINUE;
                try {
                    if (file.getFileName().toString().equals("package.json")) {
                        var json = JSON.readTree(Files.readString(file));
                        for (String group : List.of("dependencies", "devDependencies", "peerDependencies")) {
                            if (json.path(group).has("bootstrap") || json.path(group).has("react-bootstrap")) found.add(file.getParent());
                        }
                    } else if (WebkitPathConventions.isWebkitPath(file) && hasReference(Files.readString(file))) {
                        Path scope = file.getParent();
                        while (!scope.equals(root) && !Files.isRegularFile(scope.resolve("package.json"))) scope = scope.getParent();
                        found.add(scope);
                    }
                } catch (Exception ignored) {
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException error) { return FileVisitResult.CONTINUE; }
        });
        return Set.copyOf(found);
    }
}
