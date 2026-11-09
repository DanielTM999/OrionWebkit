package dtm.ide.react;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class ReactSupportTest {
    @TempDir
    Path root;

    @Test
    void detectsNestedProjectsAndIgnoresInstalledDependencies() throws Exception {
        Path app = Files.createDirectories(root.resolve("apps/frontend"));
        Files.writeString(app.resolve("package.json"), "{\"dependencies\":{\"react\":\"^19\"}}");
        Path dependency = Files.createDirectories(root.resolve("node_modules/example"));
        Files.writeString(dependency.resolve("package.json"), "{\"dependencies\":{\"react\":\"^19\"}}");
        Files.writeString(root.resolve("package.json"), "broken json");
        assertEquals(java.util.Set.of(app), ReactProjectDetector.find(root));
        Files.delete(app.resolve("package.json"));
        assertTrue(ReactProjectDetector.find(root).isEmpty());
    }

    @Test
    void discoversReactAddedAfterAnEarlierScan() throws Exception {
        assertTrue(ReactProjectDetector.find(root).isEmpty());
        Files.writeString(root.resolve("package.json"), "{\"devDependencies\":{\"react\":\"^19\"}}");
        assertEquals(java.util.Set.of(root), ReactProjectDetector.find(root));
    }

    @Test
    void createsBothTemplatesWithDetectableServerScripts() throws Exception {
        for (boolean ts : new boolean[]{false, true}) {
            Path project = ReactScaffold.project(root, ts ? "typescript-app" : "javascript-app", ts);
            assertEquals("dev", ReactProjectDetector.serverScript(project));
            assertEquals(java.util.Set.of(project), ReactProjectDetector.find(project));
            assertTrue(Files.exists(project.resolve(ts ? "src/App.tsx" : "src/App.jsx")));
            assertEquals(ts, Files.exists(project.resolve("tsconfig.json")));
        }
    }

    @Test
    void preventsOverwritesAndInvalidComponentNames() throws Exception {
        Path file = ReactScaffold.component(root, "MyComponent", true);
        String original = Files.readString(file);
        assertTrue(original.contains("export default function MyComponent()"));
        assertThrows(FileAlreadyExistsException.class, () -> ReactScaffold.component(root, "MyComponent", true));
        assertThrows(IllegalArgumentException.class, () -> ReactScaffold.component(root, "../outside", false));
        assertThrows(IllegalArgumentException.class, () -> ReactScaffold.component(root, "123", false));
        assertEquals(original, Files.readString(file));
    }

    @Test
    void choosesStartAndRejectsPackagesWithoutServerScripts() throws Exception {
        Path manifest = root.resolve("package.json");
        Files.writeString(manifest, "{\"scripts\":{\"start\":\"react-scripts start\"}}");
        assertEquals("start", ReactProjectDetector.serverScript(root));
        Files.writeString(manifest, "{\"scripts\":{\"build\":\"vite build\"}}");
        assertThrows(java.io.IOException.class, () -> ReactProjectDetector.serverScript(root));
    }
}
