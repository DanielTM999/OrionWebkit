package dtm.ide.react;

import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;
import java.util.TreeSet;

public final class ReactProjectDetector {
    private static final Set<String> EXCLUDED = Set.of("node_modules", ".git", ".orion", "dist", "build", "target", ".next");
    private static final JsonMapper JSON = new JsonMapper();

    public static Set<Path> find(Path root) throws IOException {
        Set<Path> found = new TreeSet<>();
        Files.walkFileTree(root, Set.of(), 24, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (Thread.currentThread().isInterrupted()) return FileVisitResult.TERMINATE;
                return EXCLUDED.contains(dir.getFileName().toString()) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (Thread.currentThread().isInterrupted()) return FileVisitResult.TERMINATE;
                if (file.getFileName().toString().equals("package.json") && attrs.size() < 2_000_000) {
                    try {
                        var json = JSON.readTree(Files.readString(file));
                        if (json.path("dependencies").has("react") || json.path("devDependencies").has("react")
                                || json.path("peerDependencies").has("react")) found.add(file.getParent());
                    } catch (Exception ignored) {
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException error) {
                return FileVisitResult.CONTINUE;
            }
        });
        return Set.copyOf(found);
    }

    public static String serverScript(Path root) throws IOException {
        var scripts = JSON.readTree(Files.readString(root.resolve("package.json"))).path("scripts");
        if (scripts.has("dev")) return "dev";
        if (scripts.has("start")) return "start";
        throw new IOException("O package.json não contém script dev ou start.");
    }
}
