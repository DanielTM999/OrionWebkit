package dtm.ide.editor.tokenizer;

import dtm.stools.component.panels.editor.code.prototype.Token;
import dtm.stools.component.panels.editor.code.prototype.constants.TokenType;
import dtm.stools.component.panels.editor.code.provider.TokenClassifierCodeEditorProvider;
import dtm.stools.component.panels.editor.code.provider.TokenizerCodeEditorProvider;

import java.util.ArrayList;
import java.util.List;

public class CssTokenizerProvider implements TokenizerCodeEditorProvider {

    public static final String TOKEN_SELECTOR = "CSS_SELECTOR";
    public static final String TOKEN_PROPERTY = "CSS_PROPERTY";
    public static final String TOKEN_ATRULE = "CSS_ATRULE";
    public static final String TOKEN_HEXCOLOR = "CSS_HEXCOLOR";
    public static final String TOKEN_UNIT = "CSS_UNIT";
    public static final String TOKEN_VARIABLE = "CSS_VARIABLE";

    @Override
    public boolean supportsIncremental() {
        return false;
    }

    @Override
    public synchronized java.util.Collection<Token> tokenize(
            String text, TokenClassifierCodeEditorProvider classifier) {
        String src = text == null ? "" : text;
        return tokenize(src, 0, src.length(), classifier);
    }

    synchronized List<Token> tokenize(String text, int from, int to, TokenClassifierCodeEditorProvider classifier) {
        List<Token> out = new ArrayList<>();
        int i = from;
        int depth = 0;

        while (i < to) {
            char c = text.charAt(i);

            if (c == '\r' || c == '\n') {
                int start = i;
                i++;
                if (c == '\r' && i < to && text.charAt(i) == '\n') {
                    i++;
                }
                out.add(token(text, start, i, TokenType.NEWLINE));
                continue;
            }

            if (isInlineWhitespace(c)) {
                int start = i;
                while (i < to && isInlineWhitespace(text.charAt(i))) {
                    i++;
                }
                out.add(token(text, start, i, TokenType.WHITESPACE));
                continue;
            }

            if (c == '/' && i + 1 < to && text.charAt(i + 1) == '*') {
                int start = i;
                int end = text.indexOf("*/", i + 2);
                i = end < 0 || end >= to ? to : end + 2;
                out.add(token(text, start, i, TokenType.COMMENT));
                continue;
            }

            if (c == '"' || c == '\'') {
                int start = i;
                i = scanString(text, i, c, to);
                out.add(token(text, start, i, TokenType.STRING));
                continue;
            }

            if (c == '#' && i + 1 < to && isHex(text.charAt(i + 1))) {
                int start = i;
                i++;
                while (i < to && isHex(text.charAt(i))) {
                    i++;
                }
                int len = i - start - 1;
                if (len == 3 || len == 4 || len == 6 || len == 8) {
                    out.add(token(text, start, i, TOKEN_HEXCOLOR));
                } else {
                    out.add(token(text, start, i, TokenType.SYMBOL));
                }
                continue;
            }

            if (c == '@') {
                int start = i;
                i++;
                while (i < to && isIdentPart(text.charAt(i))) {
                    i++;
                }
                out.add(token(text, start, i, TOKEN_ATRULE));
                continue;
            }

            if (c == '-' && i + 1 < to && text.charAt(i + 1) == '-') {
                int start = i;
                i += 2;
                while (i < to && isIdentPart(text.charAt(i))) {
                    i++;
                }
                out.add(token(text, start, i, TOKEN_VARIABLE));
                continue;
            }

            if (isDigit(c) || (c == '.' && i + 1 < to && isDigit(text.charAt(i + 1)))) {
                int start = i;
                i = scanNumber(text, i, to);
                out.add(token(text, start, i, TokenType.NUMBER));
                int unitStart = i;
                while (i < to && Character.isLetter(text.charAt(i))) {
                    i++;
                }
                if (i == unitStart && i < to && text.charAt(i) == '%') {
                    i++;
                }
                if (i > unitStart) {
                    out.add(token(text, unitStart, i, TOKEN_UNIT));
                }
                continue;
            }

            if (isIdentStart(c)) {
                int start = i;
                while (i < to && isIdentPart(text.charAt(i))) {
                    i++;
                }
                String word = text.substring(start, i);
                int next = skipInlineWhitespace(text, i, to);
                boolean followedByColon = next < to && text.charAt(next) == ':';
                String type;
                if (depth > 0 && followedByColon) {
                    type = TOKEN_PROPERTY;
                } else if (depth == 0) {
                    type = TOKEN_SELECTOR;
                } else {
                    String classified = classifier == null ? null : classifier.classify(word);
                    type = classified != null && !classified.isBlank() && !TokenType.UNKNOWN.equals(classified)
                            ? classified : TokenType.IDENTIFIER;
                }
                out.add(token(text, start, i, type));
                continue;
            }

            if (c == '.' || c == '#' || c == '&' || c == '>' || c == '~' || c == '*') {
                int start = i;
                i++;
                if (depth == 0 && (c == '.' || c == '#')) {
                    while (i < to && isIdentPart(text.charAt(i))) {
                        i++;
                    }
                    out.add(token(text, start, i, TOKEN_SELECTOR));
                } else {
                    out.add(token(text, start, i, TokenType.SYMBOL));
                }
                continue;
            }

            if (c == '{') {
                depth++;
                out.add(token(text, i, i + 1, TokenType.SYMBOL));
                i++;
                continue;
            }

            if (c == '}') {
                depth = Math.max(0, depth - 1);
                out.add(token(text, i, i + 1, TokenType.SYMBOL));
                i++;
                continue;
            }

            out.add(token(text, i, i + 1, TokenType.SYMBOL));
            i++;
        }

        return out;
    }

    private static int skipInlineWhitespace(String text, int i, int to) {
        while (i < to && isInlineWhitespace(text.charAt(i))) {
            i++;
        }
        return i;
    }

    private static int scanString(String text, int quoteIndex, char quote, int to) {
        int i = quoteIndex + 1;
        while (i < to) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < to) {
                i += 2;
                continue;
            }
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

    private static int scanNumber(String text, int start, int to) {
        int i = start;
        while (i < to && isDigit(text.charAt(i))) {
            i++;
        }
        if (i < to && text.charAt(i) == '.' && i + 1 < to && isDigit(text.charAt(i + 1))) {
            i++;
            while (i < to && isDigit(text.charAt(i))) {
                i++;
            }
        }
        return i;
    }

    private static Token token(String text, int start, int end, String type) {
        return new Token(start, end, type, text.substring(start, end));
    }

    private static boolean isInlineWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\f' || c == 0x0b;
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isHex(char c) {
        return isDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private static boolean isIdentStart(char c) {
        return c == '_' || c == '-' || c == '$' || Character.isLetter(c);
    }

    private static boolean isIdentPart(char c) {
        return c == '_' || c == '-' || Character.isLetterOrDigit(c);
    }
}
