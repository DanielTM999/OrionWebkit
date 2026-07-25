package dtm.ide;

import dtm.ide.api.project.editor.IdeEditorContext;
import dtm.ide.editor.tokenizer.CssTokenizerProvider;
import dtm.ide.editor.tokenizer.HtmlTokenizerProvider;
import dtm.ide.editor.tokenizer.JsTokenizerProvider;
import dtm.ide.utils.WebkitPathConventions;
import dtm.stools.component.panels.editor.code.provider.TokenizerCodeEditorProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class WebkitEditorRegistry {

    private final Map<Path, TokenizerCodeEditorProvider> tokenizers = new ConcurrentHashMap<>();
    private final Map<Path, IdeEditorContext> editorContexts = new ConcurrentHashMap<>();
    private final Set<Path> editorPaths = ConcurrentHashMap.newKeySet();

    TokenizerCodeEditorProvider tokenizerFor(Path filePath) {
        if (filePath == null || !WebkitPathConventions.isHighlightable(filePath)) {
            return null;
        }
        return tokenizers.computeIfAbsent(WebkitPathConventions.normalizePath(filePath),
                p -> {
                    if (WebkitPathConventions.isHtmlLike(p)) {
                        return new HtmlTokenizerProvider();
                    }
                    if (WebkitPathConventions.isCssLike(p)) {
                        return new CssTokenizerProvider();
                    }
                    return new JsTokenizerProvider();
                });
    }

    void trackEditor(Path normalizedPath, IdeEditorContext context) {
        if (normalizedPath == null || !WebkitPathConventions.isHighlightable(normalizedPath)) {
            return;
        }
        editorPaths.add(normalizedPath);
        if (context != null) {
            editorContexts.put(normalizedPath, context);
        }
    }

    void close(Path filePath) {
        Path normalized = WebkitPathConventions.normalizePath(filePath);
        editorPaths.remove(normalized);
        editorContexts.remove(normalized);
    }

    void delete(Path oldPath) {
        Path normalized = WebkitPathConventions.normalizePath(oldPath);
        tokenizers.remove(normalized);
        editorPaths.remove(normalized);
        editorContexts.remove(normalized);
    }

    void rename(Path oldPath, Path newPath) {
        Path oldNormalized = WebkitPathConventions.normalizePath(oldPath);
        TokenizerCodeEditorProvider provider = tokenizers.remove(oldNormalized);
        boolean wasOpenEditor = editorPaths.remove(oldNormalized);
        IdeEditorContext editorContext = editorContexts.remove(oldNormalized);
        if (newPath != null && WebkitPathConventions.isHighlightable(newPath)) {
            Path newNormalized = WebkitPathConventions.normalizePath(newPath);
            if (provider != null) {
                tokenizers.put(newNormalized, provider);
            }
            if (wasOpenEditor) {
                editorPaths.add(newNormalized);
            }
            if (editorContext != null && WebkitPathConventions.samePath(editorContext.filePath(), newPath)) {
                editorContexts.put(newNormalized, editorContext);
            }
        }
    }

    void clearProjectEditors() {
        tokenizers.clear();
        editorPaths.clear();
        editorContexts.clear();
    }

    boolean hasOpenEditors() {
        return !editorPaths.isEmpty();
    }

    IdeEditorContext editorContext(Path normalizedPath) {
        return editorContexts.get(normalizedPath);
    }

    List<Path> regularOpenHighlightablePaths() {
        return editorPaths.stream()
                .filter(Objects::nonNull)
                .filter(WebkitPathConventions::isHighlightable)
                .filter(Files::isRegularFile)
                .toList();
    }
}
