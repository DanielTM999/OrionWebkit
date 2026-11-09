package dtm.ide.lsp;

import dtm.ide.api.project.editor.FormatCodeContext;
import dtm.ide.api.project.editor.IdeFormatScope;
import dtm.ide.utils.NodeSdkUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class WebkitFormattingIntegrationTest {
    @TempDir Path root;

    @Test
    void formatsReactWithSpacesTabsAndSelectionWithoutDamagingTemplateStrings() throws Exception {
        withServer(LspServerKind.TYPESCRIPT, "typescript-language-server/lib/cli.mjs", server -> {
            Path file = root.resolve("App.tsx");
            String source = "export default function App() {\nreturn (\n<div>\n<span>Hello</span>\n</div>\n);\n}\n";
            String spaces = format(server, file, source, 2, true);
            assertTrue(spaces.contains("\n  return ("), spaces);
            assertTrue(spaces.contains("\n    <div>"), spaces);
            assertEquals(spaces, format(server, file, spaces, 2, true));
            String tabs = format(server, file, source, 4, false);
            assertTrue(tabs.contains("\n\treturn ("), tabs);
            String literal = "function f() {\nconst value = `first\n    literal }\n  exact\n`;\nreturn value;\n}\n";
            assertTrue(format(server, file, literal, 2, true).contains("`first\n    literal }\n  exact\n`"));
            String selectionSource = "function f() {\nreturn 1;\n}\n";
            var context = new FormatCodeContext(selectionSource, "return 1;", "tsx", file,
                    IdeFormatScope.SELECTION, 15, 24, 4, true, "return 1;", 2);
            var document = WebkitFormatting.document(context);
            assertEquals("  return 1;", WebkitFormatting.apply(document,
                    server.format(file, document.text(), document.range(), WebkitFormatting.options(context, document.text()))));
        });
    }

    @Test
    void formatsHtmlNestedTagsAndPreservesCrLf() throws Exception {
        withServer(LspServerKind.HTML, "vscode-langservers-extracted/bin/vscode-html-language-server", server -> {
            Path file = root.resolve("index.html");
            String source = "<div>\r\n<section>\r\n<p>Hello</p>\r\n</section>\r\n</div>";
            String result = format(server, file, source, 2, true);
            assertTrue(result.contains("\r\n  <section>"), result);
            assertTrue(result.contains("\r\n    <p>"), result);
            assertEquals(result, format(server, file, result, 2, true));
        });
    }

    @Test
    void formatsCssDeclarationsUsingConfiguredIndentation() throws Exception {
        withServer(LspServerKind.CSS, "vscode-langservers-extracted/bin/vscode-css-language-server", server -> {
            Path file = root.resolve("style.css");
            String result = format(server, file, ".card {\ncolor: red;\n}\n", 2, true);
            assertTrue(result.contains("\n  color: red;"), result);
        });
    }

    private String format(WebkitLspProcess server, Path file, String source, int indent, boolean spaces) {
        var context = new FormatCodeContext(source, source, "", file, IdeFormatScope.DOCUMENT,
                0, source.length(), 4, spaces, source, indent);
        var document = WebkitFormatting.document(context);
        return WebkitFormatting.apply(document, server.format(file, source, null, WebkitFormatting.options(context, source)));
    }

    private void withServer(LspServerKind kind, String script, java.util.function.Consumer<WebkitLspProcess> check) throws Exception {
        String modules = System.getProperty("webkit.formattingTestModules");
        assumeTrue(modules != null, "Set webkit.formattingTestModules to run installed language servers");
        Path node = NodeSdkUtils.findNodeOnPath().orElse(null);
        assumeTrue(node != null);
        Path entry = Path.of(modules).resolve(script).toAbsolutePath();
        assertTrue(Files.isRegularFile(entry));
        WebkitLspProcess server = new WebkitLspProcess(kind, ignored -> { }, ignored -> { });
        try {
            server.start(List.of(node.toString(), entry.toString(), "--stdio"), entry.getParent(), root,
                    WebkitLspSettings.initializationOptions(kind, Path.of(modules).resolve("typescript/lib").toAbsolutePath().toString()));
            assertTrue(server.isRunning(), server.getLastError());
            check.accept(server);
        } finally { server.stop(); }
    }
}
