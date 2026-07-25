package dtm.ide.utils;

import dtm.ide.api.extension.Resource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

public final class NodeSdkUtils {

    private static final long PROCESS_TIMEOUT_SECONDS = 5;

    private NodeSdkUtils() {}

    public static boolean hasSdkInResource(Resource resource) {
        if (resource == null) {
            return false;
        }

        Path sdkFolder = resource.getResourcePath()
                .resolve("sdk")
                .normalize();

        return findNodeExecutable(sdkFolder)
                .filter(NodeSdkUtils::validateNodeExecutable)
                .isPresent();
    }

    public static boolean hasSdkInPath() {
        return findNodeOnPath().isPresent();
    }

    




    public static Optional<Path> findNodeOnPath() {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null || pathEnv.isBlank()) {
            return Optional.empty();
        }

        boolean windows = System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT)
                .contains("win");
        String executable = windows ? "node.exe" : "node";

        for (String dir : pathEnv.split(System.getProperty("path.separator", ":"))) {
            if (dir == null || dir.isBlank()) {
                continue;
            }
            try {
                Path candidate = Path.of(dir.trim(), executable);
                if (Files.isRegularFile(candidate) && validateNodeExecutable(candidate)) {
                    return Optional.of(candidate.toAbsolutePath().normalize());
                }
            } catch (Exception ignored) {
            }
        }
        return Optional.empty();
    }

    public static Optional<Path> findNodeExecutable(Path sdkFolder) {
        if (sdkFolder == null || !Files.isDirectory(sdkFolder)) {
            return Optional.empty();
        }

        boolean windows = System.getProperty("os.name")
                .toLowerCase()
                .contains("win");

        List<Path> candidates;

        if (windows) {
            candidates = List.of(
                    sdkFolder.resolve("node.exe"),
                    sdkFolder.resolve("bin").resolve("node.exe")
            );
        } else {
            candidates = List.of(
                    sdkFolder.resolve("bin").resolve("node"),
                    sdkFolder.resolve("node")
            );
        }

        return candidates.stream()
                .filter(Files::isRegularFile)
                .findFirst()
                .map(path -> path.toAbsolutePath().normalize());
    }

    private static boolean validateNodeExecutable(Path executable) {
        Process process = null;

        try {
            process = new ProcessBuilder(
                    executable.toString(),
                    "--version"
            )
                    .redirectErrorStream(true)
                    .start();

            boolean finished = process.waitFor(
                    PROCESS_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS
            );

            if (!finished) {
                process.destroyForcibly();
                return false;
            }

            return process.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }
}