package dtm.ide.services.sdk;

import dtm.ide.api.exceptions.DisplayException;
import dtm.ide.api.extension.Resource;
import dtm.ide.lsp.LspServerKind;
import dtm.stools.component.popup.ModernDialog;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;






@Slf4j
public class LspPackageInstaller {

    private static final String LSP_DIR = "lsp";
    private static final long INSTALL_TIMEOUT_SECONDS = 300;

    private static final String VSCODE_LANGSERVERS_EXTRACTED = "vscode-langservers-extracted@4.10.0";
    private static final String TYPESCRIPT_LANGUAGE_SERVER = "typescript-language-server@5.3.0";
    private static final String TYPESCRIPT = "typescript@5.9.2";

    private final Object installLock = new Object();
    private final Resource resource;

    public LspPackageInstaller(Resource resource) {
        this.resource = resource;
    }

    public Optional<Path> resolveServerScript(LspServerKind kind) {
        Path lspDir = installationRoot(kind);
        if (lspDir == null) {
            return Optional.empty();
        }
        Path script = lspDir.resolve(relativeScriptPath(kind));
        return Files.isRegularFile(script) ? Optional.of(script.toAbsolutePath().normalize()) : Optional.empty();
    }

    public Path ensureServerScript(LspServerKind kind, Path nodeExecutable) {
        synchronized (installLock) {
            Optional<Path> existing = resolveServerScript(kind);
            if (existing.isPresent()) {
                return existing.get();
            }
            Path lspDir = installationRoot(kind);
            if (lspDir == null) {
                throw displayException("Diretório de recursos do plugin indisponível.", null);
            }
            try {
                Files.createDirectories(lspDir);
            } catch (IOException e) {
                throw displayException("Não foi possível preparar a pasta dos servidores de linguagem.", e);
            }
            installPackages(nodeExecutable, lspDir, packagesFor(kind));
            return resolveServerScript(kind).orElseThrow(() -> displayException(
                    "O servidor de linguagem " + kind + " foi instalado, mas o script não foi encontrado.", null));
        }
    }

    





    public Optional<Path> resolveTypescriptLibDir() {
        Path lspDir = installationRoot(LspServerKind.TYPESCRIPT);
        if (lspDir == null) {
            return Optional.empty();
        }
        Path lib = lspDir.resolve("node_modules").resolve("typescript").resolve("lib");
        return Files.isDirectory(lib) ? Optional.of(lib.toAbsolutePath().normalize()) : Optional.empty();
    }

    private static List<String> packagesFor(LspServerKind kind) {
        return switch (kind) {
            case HTML, CSS -> List.of(VSCODE_LANGSERVERS_EXTRACTED);
            case TYPESCRIPT -> List.of(TYPESCRIPT_LANGUAGE_SERVER, TYPESCRIPT);
        };
    }

    private static Path relativeScriptPath(LspServerKind kind) {
        return switch (kind) {
            case HTML -> Path.of("node_modules", "vscode-langservers-extracted", "bin", "vscode-html-language-server");
            case CSS -> Path.of("node_modules", "vscode-langservers-extracted", "bin", "vscode-css-language-server");
            case TYPESCRIPT -> Path.of("node_modules", "typescript-language-server", "lib", "cli.mjs");
        };
    }

    private void installPackages(Path nodeExecutable, Path lspDir, List<String> packages) {
        List<String> command = new ArrayList<>();
        Optional<Path> npmCli = resolveNpmCli(nodeExecutable);
        if (npmCli.isPresent()) {
            command.add(nodeExecutable.toAbsolutePath().toString());
            command.add(npmCli.get().toAbsolutePath().toString());
        } else {
            String npm = resolveNpmOnPath().orElseThrow(() -> displayException(
                    "npm não foi encontrado (nem junto ao Node.js, nem no PATH do sistema).", null));
            if (isWindows()) {
                command.add("cmd.exe");
                command.add("/c");
            }
            command.add(npm);
        }
        command.add("install");
        command.addAll(packages);
        command.add("--prefix");
        command.add(lspDir.toAbsolutePath().toString());
        command.add("--no-audit");
        command.add("--no-fund");
        command.add("--no-save");
        command.add("--loglevel=error");

        Process process = null;
        Path output = null;
        try {
            output = Files.createTempFile(lspDir, "npm-install-", ".log");
            ProcessBuilder builder = new ProcessBuilder(command)
                    .directory(lspDir.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(output.toFile());
            builder.environment().put("PATH", nodeExecutable.toAbsolutePath().getParent()
                    + System.getProperty("path.separator") + System.getenv().getOrDefault("PATH", ""));
            process = builder.start();

            boolean finished = process.waitFor(INSTALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw displayException("A instalação via npm excedeu o tempo limite.", null);
            }
            if (process.exitValue() != 0) {
                String details = installationDetails(output);
                log.warn("npm terminou com código {} em {}: {}", process.exitValue(), lspDir, details);
                throw displayException("Falha ao instalar pacotes npm: " + String.join(", ", packages)
                        + "\n" + details, null);
            }
        } catch (DisplayException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw displayException("Instalação via npm interrompida.", e);
        } catch (IOException e) {
            throw displayException("Não foi possível executar o npm.", e);
        } finally {
            if (process != null && process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
            if (output != null) {
                try { Files.deleteIfExists(output); } catch (IOException ignored) { }
            }
        }
    }

    private static Optional<Path> resolveNpmCli(Path nodeExecutable) {
        Path root = NodeSdkInstaller.rootFromExecutable(nodeExecutable);
        if (root == null) {
            return Optional.empty();
        }
        List<Path> candidates = List.of(
                root.resolve("node_modules").resolve("npm").resolve("bin").resolve("npm-cli.js"),
                root.resolve("lib").resolve("node_modules").resolve("npm").resolve("bin").resolve("npm-cli.js")
        );
        return candidates.stream().filter(Files::isRegularFile).findFirst();
    }

    private static Optional<String> resolveNpmOnPath() {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null || pathEnv.isBlank()) {
            return Optional.empty();
        }
        List<String> names = isWindows() ? List.of("npm.cmd", "npm.exe") : List.of("npm");
        for (String dir : pathEnv.split(System.getProperty("path.separator", ":"))) {
            if (dir == null || dir.isBlank()) {
                continue;
            }
            for (String name : names) {
                try {
                    Path candidate = Path.of(dir.trim(), name);
                    if (Files.isRegularFile(candidate)) {
                        return Optional.of(candidate.toAbsolutePath().normalize().toString());
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return Optional.empty();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    private Path lspRoot() {
        Path base = resourcePath();
        return base == null ? null : base.resolve("sdk").resolve(LSP_DIR).toAbsolutePath().normalize();
    }

    // npm --no-save prunes other packages in the same prefix. Keep each server
    // family isolated so installing one cannot remove or rename a running server.
    private Path installationRoot(LspServerKind kind) {
        Path root = lspRoot();
        return root == null ? null : root.resolve(kind == LspServerKind.TYPESCRIPT ? "typescript" : "web");
    }

    private Path resourcePath() {
        try {
            return resource == null ? null : resource.getResourcePath();
        } catch (Exception e) {
            return null;
        }
    }

    private static String installationDetails(Path output) {
        var lines = new java.util.ArrayDeque<String>();
        try (var reader = Files.newBufferedReader(output)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (lines.size() == 20) lines.removeFirst();
                lines.addLast(line.substring(0, Math.min(line.length(), 1000)));
            }
        } catch (IOException ignored) {
        }
        return String.join("\n", lines);
    }

    private static DisplayException displayException(String message, Throwable cause) {
        DisplayException exception = cause == null
                ? new DisplayException(message)
                : new DisplayException(message, cause);
        return exception
                .title("Erro ao configurar servidores de linguagem web")
                .type(ModernDialog.Type.ERROR)
                .draggable(true);
    }
}
