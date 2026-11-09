package dtm.ide.editor;

import dtm.ide.WebkitAdapter;
import dtm.ide.api.project.editor.IdeCompletionContext;
import dtm.ide.api.project.editor.IdeCompletionTriggerKind;
import dtm.stools.component.panels.editor.code.api.Range;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class BootstrapSupportTest {
    @TempDir
    Path root;
    private final BootstrapCompletionProvider provider = new BootstrapCompletionProvider();

    @Test
    void detectsCdnLocalStylesheetsScriptsAndReactImports() {
        for (String text : new String[]{
                "<link href=\"https://cdn.jsdelivr.net/npm/bootstrap@5.3.8/dist/css/bootstrap.min.css\" rel=\"stylesheet\">",
                "<link rel='stylesheet' href='./css/bootstrap.css'>",
                "<script src='js/bootstrap.bundle.min.js'></script>",
                "import 'bootstrap/dist/css/bootstrap.min.css';",
                "import './vendor/bootstrap.min.css';",
                "import { Button } from 'react-bootstrap';",
                "const bootstrap = require('bootstrap');",
                "@import url('bootstrap/dist/css/bootstrap.css');",
                "@use 'bootstrap/scss/bootstrap';"}) {
            assertTrue(BootstrapProjectDetector.hasReference(text), text);
        }
        assertFalse(BootstrapProjectDetector.hasReference("const bootstrap = false;"));
        assertFalse(BootstrapProjectDetector.hasReference("<!-- <link href='bootstrap.css'> -->"));
        assertFalse(BootstrapProjectDetector.hasReference("/* import 'bootstrap'; */"));
    }

    @Test
    void detectsImportsInAnotherFileAndNewDependenciesWithoutNodeModules() throws Exception {
        Path app = Files.createDirectories(root.resolve("app"));
        Files.writeString(app.resolve("package.json"), "{}");
        Files.writeString(app.resolve("main.jsx"), "import 'bootstrap/dist/css/bootstrap.min.css';");
        Path ignored = Files.createDirectories(root.resolve("node_modules/demo"));
        Files.writeString(ignored.resolve("package.json"), "{\"dependencies\":{\"bootstrap\":\"5\"}}");
        assertEquals(Set.of(app), BootstrapProjectDetector.find(root));
        Files.delete(app.resolve("main.jsx"));
        assertTrue(BootstrapProjectDetector.find(root).isEmpty());
        Files.writeString(app.resolve("package.json"), "{\"dependencies\":{\"bootstrap\":\"5\"}}");
        assertEquals(Set.of(app), BootstrapProjectDetector.find(root));
    }

    @Test
    void recognizesBootstrapImportedInPlainCssForHtmlProjects() throws Exception {
        Path css = Files.createDirectories(root.resolve("css"));
        Files.writeString(css.resolve("site.css"), "@import 'bootstrap.min.css';");
        assertEquals(Set.of(root), BootstrapProjectDetector.find(root));
    }

    @Test
    void suggestsHtmlClassesAndPreservesOtherTokens() {
        String text = "<button class=\"btn btn-pr";
        var result = provider.complete(Path.of("page.html"), text, text.length());
        assertTrue(result.exclusive());
        var item = result.items().stream().filter(value -> value.label().equals("btn-primary")).findFirst().orElseThrow();
        assertEquals("btn-primary", item.insertText());
        assertEquals(Range.of(0, text.length() - 6, 0, text.length()), item.replacementRange());
        assertFalse(result.items().stream().anyMatch(value -> value.label().equals("btn")));
    }

    @Test
    void completesResponsiveClassesInReactLiteralExpressions() {
        for (String value : new String[]{"<div className=\"col-md-", "<div className={'col-md-", "<div className={`col-md-"}) {
            var result = provider.complete(Path.of("Component.tsx"), value, value.length());
            assertTrue(result.items().stream().anyMatch(item -> item.label().equals("col-md-6")), value);
        }
    }

    @Test
    void replacesWholeClassWhenCompletingInMiddleOfToken() {
        String text = "<div\n className=\"btn-prmary\">";
        int caret = text.indexOf("btn-pr") + 6;
        var item = provider.complete(Path.of("App.jsx"), text, caret).items().stream()
                .filter(value -> value.label().equals("btn-primary")).findFirst().orElseThrow();
        assertEquals(Range.of(1, 12, 1, 22), item.replacementRange());
    }

    @Test
    void excludesClassesAlreadyPresentBeforeCaret() {
        String text = "<div class='btn btn-'";
        var result = provider.complete(Path.of("page.html"), text, text.length() - 1);
        assertFalse(result.items().stream().anyMatch(item -> item.label().equals("btn")));
        assertTrue(result.items().stream().anyMatch(item -> item.label().equals("btn-danger")));
    }

    @Test
    void completesDataAttributesAndTheirValues() {
        String attribute = "<button data-bs-to";
        var attrs = provider.complete(Path.of("page.html"), attribute, attribute.length());
        assertTrue(attrs.items().stream().anyMatch(item -> item.insertText().equals("data-bs-toggle=\"\"")));
        String value = "<button data-bs-toggle=\"mo";
        var values = provider.complete(Path.of("App.jsx"), value, value.length());
        assertTrue(values.items().stream().anyMatch(item -> item.label().equals("modal")));
    }

    @Test
    void completesCssVariablesAndSelectors() {
        String css = ".card { color: var(--bs-pr";
        assertTrue(provider.complete(Path.of("style.css"), css, css.length()).items().stream()
                .anyMatch(item -> item.label().equals("--bs-primary")));
        assertTrue(provider.complete(Path.of("style.css"), ".btn-pr", 7).items().stream()
                .anyMatch(item -> item.label().equals("btn-primary")));
    }

    @Test
    void doesNotOfferClassesInUnrelatedAttributesScriptsOrExpressions() {
        for (String text : new String[]{"<div title=\"btn-", "<script>const value = 'btn-", "<div className={value", "const x = 'btn-'", "<div>btn-", "<!-- <div class=\"btn-", "/* <div class=\"btn-"}) {
            assertTrue(provider.complete(Path.of("page.html"), text, text.length()).items().isEmpty(), text);
        }
    }

    @Test
    void allowsJsxComparisonsBeforeClassName() {
        String text = "<div hidden={count > 0} className=\"btn-pr";
        assertTrue(provider.complete(Path.of("App.tsx"), text, text.length()).items().stream()
                .anyMatch(item -> item.label().equals("btn-primary")));
    }

    @Test
    void adapterEnablesBootstrapInsideHtmlAttributesAndTyping() {
        String text = "<link href='bootstrap.min.css'>\n<button class=\"btn-pr";
        var context = context(text);
        WebkitAdapter adapter = new WebkitAdapter();
        try {
            assertTrue(adapter.getCompletionSuggestions(context).stream().anyMatch(item -> item.label().equals("btn-primary")));
            assertTrue(adapter.shouldAutoTriggerCompletion(context));
            assertTrue(adapter.getCompletionSuggestions(context("<button class=\"btn-pr")).isEmpty());
        } finally { adapter.onUnload(); }
    }

    private static IdeCompletionContext context(String text) {
        int lineStart = text.lastIndexOf('\n') + 1;
        int line = (int) text.chars().filter(c -> c == '\n').count();
        return new IdeCompletionContext(text, Path.of("page.html"), text.length(), line, text.length() - lineStart,
                text.substring(lineStart), "pr", text.length() - 2, IdeCompletionTriggerKind.TYPING);
    }
}
