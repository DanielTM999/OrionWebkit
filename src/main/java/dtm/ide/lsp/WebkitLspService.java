package dtm.ide.lsp;

import dtm.ide.api.extension.Resource;
import dtm.ide.services.sdk.LspPackageInstaller;
import dtm.ide.services.sdk.NodeSdkInstaller;
import dtm.ide.utils.WebkitPathConventions;
import dtm.request_actions.http.download.core.DownloadObserver;
import dtm.stools.component.panels.editor.code.autocomplete.AutoCompleteItem;
import dtm.stools.component.panels.editor.code.diagnostics.Diagnostic;
import dtm.stools.component.panels.editor.code.api.Location;
import dtm.stools.component.panels.editor.code.api.Range;
import dtm.stools.component.panels.editor.code.api.TextEdit;
import dtm.stools.component.panels.editor.code.api.CodeAction;
import dtm.stools.component.panels.editor.code.hover.HoverInfo;
import dtm.stools.component.panels.editor.code.signature.SignatureHelp;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;






@Slf4j
public class WebkitLspService {

    private static final long HEALTH_CHECK_INTERVAL_SECONDS = 15;

    private final NodeSdkInstaller nodeSdkInstaller;
    private final LspPackageInstaller lspPackageInstaller;
    private final Map<LspServerKind, WebkitLspProcess> processes = new ConcurrentHashMap<>();
    private final Map<LspServerKind, Path> projectRoots = new ConcurrentHashMap<>();
    private final ScheduledExecutorService healthCheckExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "orion-webkit-lsp-health-check");
        thread.setDaemon(true);
        return thread;
    });
    private volatile Consumer<Path> diagnosticsListener;
    private volatile Consumer<LspServerKind> recoveryListener;

    public WebkitLspService(Resource resource, DownloadObserver downloadObserver) {
        this.nodeSdkInstaller = new NodeSdkInstaller(resource, downloadObserver);
        this.lspPackageInstaller = new LspPackageInstaller(resource);
        healthCheckExecutor.scheduleWithFixedDelay(
                this::checkRunningProcesses,
                HEALTH_CHECK_INTERVAL_SECONDS,
                HEALTH_CHECK_INTERVAL_SECONDS,
                TimeUnit.SECONDS);
    }

    public static Optional<LspServerKind> kindFor(Path filePath) {
        if (filePath == null) {
            return Optional.empty();
        }
        if (WebkitPathConventions.isHtmlLike(filePath)) {
            return Optional.of(LspServerKind.HTML);
        }
        if (WebkitPathConventions.isCssLike(filePath)) {
            return Optional.of(LspServerKind.CSS);
        }
        if (WebkitPathConventions.isJsLike(filePath)) {
            return Optional.of(LspServerKind.TYPESCRIPT);
        }
        return Optional.empty();
    }

    



    public void setDiagnosticsListener(Consumer<Path> listener) {
        this.diagnosticsListener = listener;
    }

    public void setRecoveryListener(Consumer<LspServerKind> listener) {
        this.recoveryListener = listener;
    }

    public boolean isRunning(LspServerKind kind) {
        WebkitLspProcess process = processes.get(kind);
        return process != null && process.isRunning();
    }

    public void startForFile(Path filePath, Path projectRoot, DownloadProgressListener progressListener) {
        kindFor(filePath).ifPresent(kind -> start(kind, projectRoot, progressListener));
    }

    public void start(LspServerKind kind, Path projectRoot, DownloadProgressListener progressListener) {
        WebkitLspProcess process = processes.computeIfAbsent(kind, this::newProcess);
        Path normalizedRoot = normalizeRoot(projectRoot);
        if (process.isRunning() && hasSameRoot(kind, normalizedRoot)) {
            return;
        }
        if (process.isRunning()) {
            process.stop();
            projectRoots.remove(kind);
        }
        DownloadProgressListener listener = progressListener == null ? DownloadProgressListener.NOOP : progressListener;
        String progressId = "webkitLsp." + kind.name().toLowerCase(Locale.ROOT);
        listener.onStart(progressId, "Preparando servidor " + displayName(kind) + "...");
        try {
            Path node = nodeSdkInstaller.ensureNode(toNodeListener(listener));
            if (lspPackageInstaller.resolveServerScript(kind).isEmpty()) {
                listener.onProgress(progressId,
                        "Instalando dependências do servidor " + displayName(kind) + "...", -1);
            }
            Path script = lspPackageInstaller.ensureServerScript(kind, node);
            listener.onProgress(progressId, "Analisando projeto (" + displayName(kind) + ")...", -1);
            List<String> command = List.of(node.toAbsolutePath().toString(), script.toAbsolutePath().toString(), "--stdio");
            process.start(command, script.getParent(), projectRoot, initializationOptionsFor(kind));
            if (process.isRunning()) {
                if (normalizedRoot == null) {
                    projectRoots.remove(kind);
                } else {
                    projectRoots.put(kind, normalizedRoot);
                }
            } else {
                projectRoots.remove(kind);
            }
        } catch (Exception e) {
            projectRoots.remove(kind);
            log.warn("Falha ao preparar o servidor de linguagem {}: {}", kind, e.getMessage());
        } finally {
            listener.onFinish(progressId);
        }
    }

    private static String displayName(LspServerKind kind) {
        return switch (kind) {
            case HTML -> "HTML";
            case CSS -> "CSS";
            case TYPESCRIPT -> "TypeScript/JavaScript";
        };
    }

    private static Path normalizeRoot(Path projectRoot) {
        return projectRoot == null ? null : projectRoot.toAbsolutePath().normalize();
    }

    private boolean hasSameRoot(LspServerKind kind, Path normalizedRoot) {
        return normalizedRoot == null
                ? !projectRoots.containsKey(kind)
                : Objects.equals(projectRoots.get(kind), normalizedRoot);
    }

    



    public void sync(Path filePath, String text) {
        kindFor(filePath).ifPresent(kind -> {
            WebkitLspProcess process = processes.get(kind);
            if (process != null && process.isRunning()) {
                process.syncDocument(filePath, text);
            }
        });
    }

    




    public List<AutoCompleteItem> complete(Path filePath, String text, int line, int character,
                                           String prefix, Character triggerChar) {
        return kindFor(filePath)
                .map(processes::get)
                .filter(WebkitLspProcess::isRunning)
                .map(process -> process.complete(filePath, text, line, character, prefix, triggerChar))
                .orElse(List.of());
    }

    public boolean isRunningFor(Path filePath) {
        return kindFor(filePath).map(this::isRunning).orElse(false);
    }

    public List<Location> definitions(Path file, String text, int line, int character, boolean references) {
        return kindFor(file).map(processes::get).filter(WebkitLspProcess::isRunning)
                .map(p -> p.definitions(file, text, line, character, references)).orElse(List.of());
    }

    public List<Range> highlights(Path file, String text, int line, int character) {
        return kindFor(file).map(processes::get).filter(WebkitLspProcess::isRunning)
                .map(p -> p.highlights(file, text, line, character)).orElse(List.of());
    }

    public HoverInfo hover(Path file, String text, int line, int character) {
        return kindFor(file).map(processes::get).filter(WebkitLspProcess::isRunning)
                .map(p -> p.hover(file, text, line, character)).orElse(null);
    }

    public SignatureHelp signatureHelp(Path file, String text, int line, int character) {
        return kindFor(file).map(processes::get).filter(WebkitLspProcess::isRunning)
                .map(p -> p.signatureHelp(file, text, line, character)).orElse(null);
    }

    public List<TextEdit> rename(Path file, String text, int line, int character, String newName) {
        return kindFor(file).map(processes::get).filter(WebkitLspProcess::isRunning)
                .map(p -> p.rename(file, text, line, character, newName)).orElse(List.of());
    }

    public List<CodeAction> codeActions(Path file, String text, Range range) {
        return kindFor(file).map(processes::get).filter(WebkitLspProcess::isRunning)
                .map(p -> p.codeActions(file, text, range)).orElse(List.of());
    }

    public String format(dtm.ide.api.project.editor.FormatCodeContext context) {
        WebkitFormatting.Document document = WebkitFormatting.document(context);
        WebkitLspProcess process = kindFor(context.file()).map(processes::get).orElse(null);
        if (process == null || !process.isRunning()) return document.source();
        try {
            return WebkitFormatting.apply(document, process.format(context.file(), document.text(),
                    document.selection() ? document.range() : null, WebkitFormatting.options(context, document.text())));
        } catch (IllegalArgumentException error) {
            log.debug("Formatting edits rejected for {}: {}", context.file(), error.getMessage());
            return document.source();
        }
    }

    




    public void closeDocument(Path filePath) {
        kindFor(filePath).map(processes::get)
                .filter(WebkitLspProcess::isRunning)
                .ifPresent(process -> process.closeDocument(filePath));
    }

    



    public Set<Character> completionTriggerCharacters() {
        Set<Character> characters = new HashSet<>();
        processes.values().stream()
                .filter(WebkitLspProcess::isRunning)
                .forEach(process -> characters.addAll(process.completionTriggerCharacters()));
        return characters;
    }

    public List<Diagnostic> diagnostics(Path filePath) {
        return kindFor(filePath)
                .map(processes::get)
                .filter(WebkitLspProcess::isRunning)
                .map(process -> process.diagnostics(filePath))
                .orElse(List.of());
    }

    public void stop(LspServerKind kind) {
        WebkitLspProcess process = processes.get(kind);
        if (process != null) {
            process.stop();
        }
        projectRoots.remove(kind);
    }

    public void restart(LspServerKind kind, Path projectRoot, DownloadProgressListener progressListener) {
        stop(kind);
        start(kind, projectRoot, progressListener);
    }

    public void stopAll() {
        processes.values().forEach(WebkitLspProcess::stop);
        projectRoots.clear();
    }

    private void checkRunningProcesses() {
        processes.values().stream()
                .filter(WebkitLspProcess::isRunning)
                .forEach(process -> {
                    try {
                        process.probeResponsiveness();
                    } catch (Exception e) {
                        log.debug("Falha no health check do servidor {}: {}",
                                process.kind(), e.getMessage());
                    }
                });
    }

    private WebkitLspProcess newProcess(LspServerKind kind) {
        return new WebkitLspProcess(kind, this::onDiagnosticsPublished, this::onRecoveryRequired);
    }

    private void onRecoveryRequired(LspServerKind kind) {
        Consumer<LspServerKind> listener = recoveryListener;
        if (listener != null) {
            listener.accept(kind);
        }
    }

    private void onDiagnosticsPublished(String uri) {
        Consumer<Path> listener = diagnosticsListener;
        if (listener == null) {
            return;
        }
        Path file = pathFromUri(uri);
        if (file != null) {
            listener.accept(file);
        }
    }

    private static Path pathFromUri(String uri) {
        try {
            return uri == null || uri.isBlank() ? null : Path.of(URI.create(uri));
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Object> initializationOptionsFor(LspServerKind kind) {
        String typescriptLibDir = kind == LspServerKind.TYPESCRIPT
                ? lspPackageInstaller.resolveTypescriptLibDir().map(Path::toString).orElse(null)
                : null;
        return WebkitLspSettings.initializationOptions(kind, typescriptLibDir);
    }

    private static NodeSdkInstaller.DownloadProgressListener toNodeListener(DownloadProgressListener listener) {
        return new NodeSdkInstaller.DownloadProgressListener() {
            @Override
            public void onStart(String id, String label) {
                listener.onStart(id, label);
            }

            @Override
            public void onProgress(String id, String label, int percent) {
                listener.onProgress(id, label, percent);
            }

            @Override
            public void onFinish(String id) {
                listener.onFinish(id);
            }
        };
    }

    public interface DownloadProgressListener {
        DownloadProgressListener NOOP = new DownloadProgressListener() {
        };

        default void onStart(String id, String label) {
        }

        default void onProgress(String id, String label, int percent) {
        }

        default void onFinish(String id) {
        }
    }
}
