package dtm.ide.services.sdk;

import dtm.ide.api.exceptions.DisplayException;
import dtm.ide.api.extension.Resource;
import dtm.ide.lsp.LspServerKind;
import dtm.stools.component.popup.ModernDialog;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
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
        Path lspDir = lspRoot();
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
            Path lspDir = lspRoot();
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
        Path lspDir = lspRoot();
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

        try {
            Process process = new ProcessBuilder(command)
                    .directory(lspDir.toFile())
                    .redirectErrorStream(true)
                    .start();
            Thread drain = new Thread(() -> drainQuietly(process.getInputStream()), "orion-webkit-npm-install");
            drain.setDaemon(true);
            drain.start();

            boolean finished = process.waitFor(INSTALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw displayException("A instalação via npm excedeu o tempo limite.", null);
            }
            if (process.exitValue() != 0) {
                throw displayException("Falha ao instalar pacotes npm: " + String.join(", ", packages), null);
            }
        } catch (DisplayException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw displayException("Instalação via npm interrompida.", e);
        } catch (IOException e) {
            throw displayException("Não foi possível executar o npm.", e);
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

    private Path resourcePath() {
        try {
            return resource == null ? null : resource.getResourcePath();
        } catch (Exception e) {
            return null;
        }
    }

    private static void drainQuietly(InputStream in) {
        try (in) {
            byte[] buffer = new byte[8192];
            while (in.read(buffer) != -1) {
                
            }
        } catch (IOException ignored) {
        }
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
