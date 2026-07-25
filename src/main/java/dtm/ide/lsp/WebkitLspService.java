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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;






@Slf4j
public class WebkitLspService {

    private final NodeSdkInstaller nodeSdkInstaller;
    private final LspPackageInstaller lspPackageInstaller;
    private final Map<LspServerKind, WebkitLspProcess> processes = new ConcurrentHashMap<>();
    private volatile Consumer<Path> diagnosticsListener;

    public WebkitLspService(Resource resource, DownloadObserver downloadObserver) {
        this.nodeSdkInstaller = new NodeSdkInstaller(resource, downloadObserver);
        this.lspPackageInstaller = new LspPackageInstaller(resource);
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

    public boolean isRunning(LspServerKind kind) {
        WebkitLspProcess process = processes.get(kind);
        return process != null && process.isRunning();
    }

    public void startForFile(Path filePath, Path projectRoot, DownloadProgressListener progressListener) {
        kindFor(filePath).ifPresent(kind -> start(kind, projectRoot, progressListener));
    }

    public void start(LspServerKind kind, Path projectRoot, DownloadProgressListener progressListener) {
        WebkitLspProcess process = processes.computeIfAbsent(kind, this::newProcess);
        if (process.isRunning()) {
            return;
        }
        DownloadProgressListener listener = progressListener == null ? DownloadProgressListener.NOOP : progressListener;
        String progressId = "webkitLsp." + kind.name().toLowerCase(Locale.ROOT);
        listener.onStart(progressId, "Preparando servidor de linguagem (" + kind + ")");
        try {
            Path node = nodeSdkInstaller.ensureNode(toNodeListener(listener));
            Path script = lspPackageInstaller.ensureServerScript(kind, node);
            List<String> command = List.of(node.toAbsolutePath().toString(), script.toAbsolutePath().toString(), "--stdio");
            process.start(command, script.getParent(), projectRoot, initializationOptionsFor(kind));
        } catch (Exception e) {
            log.warn("Falha ao preparar o servidor de linguagem {}: {}", kind, e.getMessage());
        } finally {
            listener.onFinish(progressId);
        }
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
    }

    public void stopAll() {
        processes.values().forEach(WebkitLspProcess::stop);
    }

    private WebkitLspProcess newProcess(LspServerKind kind) {
        return new WebkitLspProcess(kind, this::onDiagnosticsPublished);
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
