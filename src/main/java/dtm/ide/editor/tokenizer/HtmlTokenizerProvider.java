package dtm.ide.editor.tokenizer;

import dtm.stools.component.panels.editor.code.prototype.Token;
import dtm.stools.component.panels.editor.code.prototype.constants.TokenType;
import dtm.stools.component.panels.editor.code.provider.TokenClassifierCodeEditorProvider;
import dtm.stools.component.panels.editor.code.provider.TokenizerCodeEditorProvider;

import java.util.ArrayList;
import java.util.List;





public class HtmlTokenizerProvider implements TokenizerCodeEditorProvider {

    public static final String TOKEN_TAG = "HTML_TAG";
    public static final String TOKEN_ATTRIBUTE = "HTML_ATTRIBUTE";
    public static final String TOKEN_ENTITY = "HTML_ENTITY";
    public static final String TOKEN_DOCTYPE = "HTML_DOCTYPE";

    private final JsTokenizerProvider js = new JsTokenizerProvider();
    private final CssTokenizerProvider css = new CssTokenizerProvider();

    @Override
    public boolean supportsIncremental() {
        return false;
    }

    @Override
    public synchronized java.util.Collection<Token> tokenize(
            String text, TokenClassifierCodeEditorProvider classifier) {
        String src = text == null ? "" : text;
        List<Token> out = new ArrayList<>();
        int n = src.length();
        int i = 0;

        while (i < n) {
            char c = src.charAt(i);

            if (c == '<' && src.startsWith("<!--", i)) {
                int end = src.indexOf("-->", i + 4);
                end = end < 0 ? n : end + 3;
                out.add(token(src, i, end, TokenType.COMMENT));
                i = end;
                continue;
            }

            if (c == '<' && src.regionMatches(true, i, "<!DOCTYPE", 0, 9)) {
                int end = src.indexOf('>', i);
                end = end < 0 ? n : end + 1;
                out.add(token(src, i, end, TOKEN_DOCTYPE));
                i = end;
                continue;
            }

            if (c == '<') {
                i = scanTag(src, i, out, classifier);
                continue;
            }

            if (c == '&') {
                int end = scanEntity(src, i);
                out.add(token(src, i, end, end > i + 1 ? TOKEN_ENTITY : TokenType.IDENTIFIER));
                i = end;
                continue;
            }

            if (c == '\r' || c == '\n') {
                int start = i;
                i++;
                if (c == '\r' && i < n && src.charAt(i) == '\n') {
                    i++;
                }
                out.add(token(src, start, i, TokenType.NEWLINE));
                continue;
            }

            if (isInlineWhitespace(c)) {
                int start = i;
                while (i < n && isInlineWhitespace(src.charAt(i))) {
                    i++;
                }
                out.add(token(src, start, i, TokenType.WHITESPACE));
                continue;
            }

            i = scanText(src, i, out);
        }

        return out;
    }

    private int scanText(String text, int at, List<Token> out) {
        int n = text.length();
        int i = at;
        while (i < n && !isTextBoundary(text.charAt(i))) {
            i++;
        }
        if (i == at) {
            i++;
        }
        out.add(token(text, at, i, TokenType.IDENTIFIER));
        return i;
    }

    private static boolean isTextBoundary(char c) {
        return c == '<' || c == '&' || c == '\r' || c == '\n' || isInlineWhitespace(c);
    }

    private static int scanEntity(String text, int at) {
        int n = text.length();
        int i = at + 1;
        if (i < n && text.charAt(i) == '#') {
            i++;
            boolean hex = i < n && (text.charAt(i) == 'x' || text.charAt(i) == 'X');
            if (hex) {
                i++;
            }
            int digitsStart = i;
            while (i < n && (hex ? isHexDigit(text.charAt(i)) : isDigit(text.charAt(i)))) {
                i++;
            }
            if (i > digitsStart && i < n && text.charAt(i) == ';') {
                return i + 1;
            }
            return at + 1;
        }
        int nameStart = i;
        while (i < n && Character.isLetterOrDigit(text.charAt(i))) {
            i++;
        }
        if (i > nameStart && i < n && text.charAt(i) == ';') {
            return i + 1;
        }
        return at + 1;
    }

    private int scanTag(String text, int at, List<Token> out, TokenClassifierCodeEditorProvider classifier) {
        int n = text.length();
        int i = at + 1;
        out.add(token(text, at, at + 1, TokenType.SYMBOL));
        boolean closing = false;
        if (i < n && text.charAt(i) == '/') {
            out.add(token(text, i, i + 1, TokenType.SYMBOL));
            closing = true;
            i++;
        }

        int wsStart = i;
        while (i < n && isInlineWhitespace(text.charAt(i))) {
            i++;
        }
        if (i > wsStart) {
            out.add(token(text, wsStart, i, TokenType.WHITESPACE));
        }

        String tagName = "";
        if (i < n && isTagNameStart(text.charAt(i))) {
            int nameStart = i;
            while (i < n && isTagNamePart(text.charAt(i))) {
                i++;
            }
            tagName = text.substring(nameStart, i).toLowerCase(java.util.Locale.ROOT);
            out.add(token(text, nameStart, i, TOKEN_TAG));
        }

        while (i < n) {
            char c = text.charAt(i);
            if (c == '>') {
                out.add(token(text, i, i + 1, TokenType.SYMBOL));
                i++;
                if (!closing && ("script".equals(tagName) || "style".equals(tagName))) {
                    i = emitEmbedded(text, i, out, classifier, tagName);
                }
                return i;
            }
            if (c == '/' && i + 1 < n && text.charAt(i + 1) == '>') {
                out.add(token(text, i, i + 2, TokenType.SYMBOL));
                return i + 2;
            }
            if (isInlineWhitespace(c) || c == '\r' || c == '\n') {
                int start = i;
                while (i < n && (isInlineWhitespace(text.charAt(i)) || text.charAt(i) == '\r' || text.charAt(i) == '\n')) {
                    i++;
                }
                out.add(token(text, start, i, containsNewline(text, start, i) ? TokenType.NEWLINE : TokenType.WHITESPACE));
                continue;
            }
            if (c == '"' || c == '\'') {
                int end = skipString(text, i, c, n);
                out.add(token(text, i, end, TokenType.STRING));
                i = end;
                continue;
            }
            if (c == '=') {
                out.add(token(text, i, i + 1, TokenType.SYMBOL));
                i++;
                continue;
            }
            if (isAttributeNameStart(c)) {
                int start = i;
                while (i < n && isAttributeNamePart(text.charAt(i))) {
                    i++;
                }
                out.add(token(text, start, i, TOKEN_ATTRIBUTE));
                continue;
            }
            out.add(token(text, i, i + 1, TokenType.SYMBOL));
            i++;
        }
        return i;
    }

    private int emitEmbedded(String text, int start, List<Token> out,
                             TokenClassifierCodeEditorProvider classifier, String tagName) {
        int n = text.length();
        String closeTag = "</" + tagName;
        int close = indexOfIgnoreCase(text, closeTag, start);
        int contentEnd = close < 0 ? n : close;

        if (contentEnd > start) {
            List<Token> embedded = "script".equals(tagName)
                    ? js.tokenizeRange(text, start, contentEnd, classifier)
                    : css.tokenize(text, start, contentEnd, classifier);
            out.addAll(embedded);
        }

        if (close < 0) {
            return n;
        }

        int i = close;
        out.add(token(text, i, i + 2, TokenType.SYMBOL));
        i += 2;
        int nameStart = i;
        while (i < n && isTagNamePart(text.charAt(i))) {
            i++;
        }
        out.add(token(text, nameStart, i, TOKEN_TAG));
        int gt = text.indexOf('>', i);
        if (gt >= 0) {
            if (gt > i) {
                out.add(token(text, i, gt, TokenType.WHITESPACE));
            }
            out.add(token(text, gt, gt + 1, TokenType.SYMBOL));
            return gt + 1;
        }
        return i;
    }

    private static int indexOfIgnoreCase(String text, String needle, int from) {
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        return lower.indexOf(needle.toLowerCase(java.util.Locale.ROOT), from);
    }

    private static int skipString(String text, int quoteIndex, char quote, int n) {
        int i = quoteIndex + 1;
        while (i < n) {
            char c = text.charAt(i);
            if (c == quote) {
                return i + 1;
            }
            if (c == '\n') {
                return i;
            }
            i++;
        }
        return i;
    }

    private static Token token(String text, int start, int end, String type) {
        return new Token(start, end, type, text.substring(start, end));
    }

    private static boolean containsNewline(String text, int start, int end) {
        for (int i = start; i < end; i++) {
            char c = text.charAt(i);
            if (c == '\r' || c == '\n') {
                return true;
            }
        }
        return false;
    }

    private static boolean isInlineWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\f' || c == 0x0b;
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isHexDigit(char c) {
        return isDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private static boolean isTagNameStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean isTagNamePart(char c) {
        return Character.isLetterOrDigit(c) || c == '-' || c == ':' || c == '.' || c == '_';
    }

    private static boolean isAttributeNameStart(char c) {
        return c == '@' || c == ':' || c == '#' || c == '[' || c == '_' || Character.isLetter(c);
    }

    private static boolean isAttributeNamePart(char c) {
        return Character.isLetterOrDigit(c) || c == '-' || c == ':' || c == '.' || c == '_'
                || c == '@' || c == '[' || c == ']' || c == '!';
    }
}
