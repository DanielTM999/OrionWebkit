package dtm.ide.lsp;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Coalesces repeated startup requests while retaining the most recent document
 * that must be synchronized once a language server is ready.
 */
public final class LspStartupCoordinator {

    private final ConcurrentMap<LspServerKind, Long> inFlight = new ConcurrentHashMap<>();
    private final ConcurrentMap<LspServerKind, ConcurrentMap<Path, PendingDocument>> pendingDocuments =
            new ConcurrentHashMap<>();

    public boolean requestStart(LspServerKind kind, long generation, Path file, String text) {
        if (kind == null || file == null) {
            return false;
        }
        pendingDocuments.computeIfAbsent(kind, ignored -> new ConcurrentHashMap<>())
                .put(file, new PendingDocument(generation, file, text == null ? "" : text));
        while (true) {
            Long activeGeneration = inFlight.putIfAbsent(kind, generation);
            if (activeGeneration == null) {
                return true;
            }
            if (activeGeneration == generation) {
                return false;
            }
            if (inFlight.replace(kind, activeGeneration, generation)) {
                return true;
            }
        }
    }

    public List<PendingDocument> pendingDocuments(LspServerKind kind, long generation) {
        ConcurrentMap<Path, PendingDocument> documents = pendingDocuments.get(kind);
        if (documents == null) {
            return List.of();
        }
        return documents.values().stream()
                .filter(pending -> pending.generation() == generation)
                .toList();
    }

    public void finish(LspServerKind kind, long generation) {
        inFlight.remove(kind, generation);
        pendingDocuments.computeIfPresent(kind, (ignored, documents) -> {
            documents.entrySet().removeIf(entry -> entry.getValue().generation() == generation);
            return documents.isEmpty() ? null : documents;
        });
    }

    public void invalidate() {
        inFlight.clear();
        pendingDocuments.clear();
    }

    public record PendingDocument(long generation, Path file, String text) {
    }
}
