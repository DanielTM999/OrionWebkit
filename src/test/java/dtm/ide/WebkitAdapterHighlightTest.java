package dtm.ide;

import dtm.ide.api.project.editor.IdeEditorContext;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebkitAdapterHighlightTest {

    @Test
    void appliesHighlightAfterSupportedEditorIsSelected() throws Exception {
        WebkitAdapter adapter = new WebkitAdapter();
        EditorState state = new EditorState(Path.of("project", "app.js"));
        IdeEditorContext context = editorContext(state);

        adapter.configureEditor(context);
        assertTrue(state.highlightEnabled.get());
        assertEquals(0, state.applyCalls.get());

        adapter.onEditorSelected(context);
        SwingUtilities.invokeAndWait(() -> { });

        assertEquals(1, state.applyCalls.get());
    }

    @Test
    void ignoresFilesWithoutWebHighlightSupport() throws Exception {
        WebkitAdapter adapter = new WebkitAdapter();
        EditorState state = new EditorState(Path.of("project", "notes.txt"));
        IdeEditorContext context = editorContext(state);

        adapter.configureEditor(context);
        adapter.onEditorSelected(context);
        SwingUtilities.invokeAndWait(() -> { });

        assertEquals(0, state.applyCalls.get());
    }

    private static IdeEditorContext editorContext(EditorState state) {
        return (IdeEditorContext) Proxy.newProxyInstance(
                IdeEditorContext.class.getClassLoader(),
                new Class<?>[]{IdeEditorContext.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "filePath" -> state.path;
                    case "getText" -> "const value = 1;";
                    case "setSyntaxHighlightEnabled" -> {
                        state.highlightEnabled.set((boolean) args[0]);
                        yield null;
                    }
                    case "applySyntaxHighlight" -> {
                        state.applyCalls.incrementAndGet();
                        yield null;
                    }
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "TestIdeEditorContext[" + state.path + "]";
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        return null;
    }

    private record EditorState(Path path, AtomicBoolean highlightEnabled, AtomicInteger applyCalls) {
        private EditorState(Path path) {
            this(path, new AtomicBoolean(), new AtomicInteger());
        }
    }
}
