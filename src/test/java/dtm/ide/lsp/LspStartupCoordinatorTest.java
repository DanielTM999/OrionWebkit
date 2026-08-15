package dtm.ide.lsp;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LspStartupCoordinatorTest {

    @Test
    void coalescesRepeatedRequestsAndKeepsAllLatestDocuments() {
        LspStartupCoordinator coordinator = new LspStartupCoordinator();
        Path first = Path.of("project", "first.js");
        Path latest = Path.of("project", "latest.js");

        assertTrue(coordinator.requestStart(LspServerKind.TYPESCRIPT, 1, first, "const first = 1;"));
        assertFalse(coordinator.requestStart(LspServerKind.TYPESCRIPT, 1, latest, "const latest = 2;"));
        assertFalse(coordinator.requestStart(LspServerKind.TYPESCRIPT, 1, first, "const first = 3;"));

        var pending = coordinator.pendingDocuments(LspServerKind.TYPESCRIPT, 1);
        assertEquals(2, pending.size());
        assertEquals("const first = 3;", pending.stream()
                .filter(document -> document.file().equals(first))
                .findFirst().orElseThrow().text());

        coordinator.finish(LspServerKind.TYPESCRIPT, 1);
        assertTrue(coordinator.pendingDocuments(LspServerKind.TYPESCRIPT, 1).isEmpty());
        assertTrue(coordinator.requestStart(LspServerKind.TYPESCRIPT, 1, latest, "latest"));
    }

    @Test
    void letsDifferentServerKindsStartIndependently() {
        LspStartupCoordinator coordinator = new LspStartupCoordinator();

        assertTrue(coordinator.requestStart(LspServerKind.HTML, 7, Path.of("index.html"), "<html>"));
        assertTrue(coordinator.requestStart(LspServerKind.CSS, 7, Path.of("style.css"), "body {}"));
        assertTrue(coordinator.requestStart(LspServerKind.TYPESCRIPT, 7, Path.of("app.js"), ""));
    }

    @Test
    void invalidatesPendingWorkWhenProjectGenerationChanges() {
        LspStartupCoordinator coordinator = new LspStartupCoordinator();
        Path oldFile = Path.of("old", "app.js");
        Path newFile = Path.of("new", "app.js");

        assertTrue(coordinator.requestStart(LspServerKind.TYPESCRIPT, 2, oldFile, "old"));
        coordinator.invalidate();

        assertTrue(coordinator.pendingDocuments(LspServerKind.TYPESCRIPT, 2).isEmpty());
        assertTrue(coordinator.requestStart(LspServerKind.TYPESCRIPT, 3, newFile, "new"));
        assertEquals(newFile, coordinator.pendingDocuments(LspServerKind.TYPESCRIPT, 3).getFirst().file());

        coordinator.finish(LspServerKind.TYPESCRIPT, 2);
        assertEquals(newFile, coordinator.pendingDocuments(LspServerKind.TYPESCRIPT, 3).getFirst().file());
    }
}
