package dtm.ide.lsp;

import dtm.ide.api.project.editor.FormatCodeContext;
import dtm.ide.api.project.editor.IdeFormatScope;
import dtm.stools.component.panels.editor.code.api.Range;
import dtm.stools.component.panels.editor.code.api.TextEdit;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class WebkitFormattingTest {
    @Test
    void usesIndentSizeIndependentlyOfTabWidth() {
        var spaces = context("x", "x", 0, 1, false, 8, true, 2);
        assertEquals(2, WebkitFormatting.options(spaces, "x").get("tabSize"));
        assertEquals(2, WebkitFormatting.options(spaces, "x").get("indentSize"));
        var tabs = context("x", "x", 0, 1, false, 8, false, 4);
        assertEquals(8, WebkitFormatting.options(tabs, "x").get("tabSize"));
        assertEquals(4, WebkitFormatting.options(tabs, "x").get("indentSize"));
        assertEquals(false, WebkitFormatting.options(tabs, "x").get("insertSpaces"));
    }

    @Test
    void appliesEditsInReverseOrderWithoutChangingLineEndings() {
        String source = "function f() {\r\nreturn 1;\r\n}";
        var document = WebkitFormatting.document(context(source, source, 0, source.length(), false, 4, true, 2));
        String formatted = WebkitFormatting.apply(document, List.of(
                new TextEdit(Range.point(1, 0), "  "),
                new TextEdit(Range.of(1, 7, 1, 8), "2")));
        assertEquals("function f() {\r\n  return 2;\r\n}", formatted);
        assertEquals("\r\n", WebkitFormatting.options(context(source, source, 0, source.length(), false, 4, true, 2), source).get("newLineCharacter"));
    }

    @Test
    void formatsSelectionWithSurroundingDocumentContext() {
        String source = "function f() {\nreturn 1;\n}";
        int start = source.indexOf("return");
        var document = WebkitFormatting.document(context(source, "return 1;", start, start + 9, true, 4, true, 2));
        assertEquals(source, document.text());
        assertEquals(Range.of(1, 0, 1, 9), document.range());
        assertEquals("  return 1;", WebkitFormatting.apply(document, List.of(new TextEdit(Range.point(1, 0), "  "))));
    }

    @Test
    void ignoresEditsOutsideSelectionAndRejectsInvalidEdits() {
        String source = "first\nsecond\nlast";
        var document = WebkitFormatting.document(context(source, "second", 6, 12, true, 4, true, 2));
        assertEquals("second", WebkitFormatting.apply(document, List.of(new TextEdit(Range.of(0, 0, 0, 5), "before"))));
        assertThrows(IllegalArgumentException.class, () -> WebkitFormatting.apply(document,
                List.of(new TextEdit(Range.of(20, 0, 20, 1), "bad"))));
    }

    @Test
    void preservesChainedFormattingAndLiteralText() {
        String source = "function f(){\nreturn `a\n  }literal`;\n}";
        var document = WebkitFormatting.document(context(source, source, 0, source.length(), false, 4, true, 2)
                .withChainedText(source.replace("function f(){", "function f() {")));
        assertTrue(document.text().startsWith("function f() {"));
        assertTrue(WebkitFormatting.apply(document, List.of()).contains("`a\n  }literal`"));
    }

    static FormatCodeContext context(String full, String selected, int start, int end, boolean selection, int tab, boolean spaces, int indent) {
        return new FormatCodeContext(full, selected, "tsx", Path.of("App.tsx"),
                selection ? IdeFormatScope.SELECTION : IdeFormatScope.DOCUMENT, start, end, tab, spaces, selected, indent);
    }
}
