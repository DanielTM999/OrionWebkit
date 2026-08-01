package dtm.ide.editor.tokenizer;

import dtm.stools.component.panels.editor.code.prototype.Token;
import dtm.stools.component.panels.editor.code.prototype.constants.TokenType;
import dtm.stools.component.panels.editor.code.provider.TokenClassifierCodeEditorProvider;
import dtm.stools.component.panels.editor.code.provider.TokenizerCodeEditorProvider;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class JsTokenizerProvider implements TokenizerCodeEditorProvider {

    public static final String TOKEN_CLASS = "CLASS";
    public static final String TOKEN_VARIABLE = "VARIABLE";
    public static final String TOKEN_CONSTANT = "CONSTANT";
    public static final String TOKEN_FUNCTION = "FUNCTION";
    public static final String TOKEN_TYPE = "TYPE";
    public static final String TOKEN_PROPERTY = "PROPERTY";
    public static final String TOKEN_DECORATOR = "DECORATOR";
    public static final String TOKEN_TAG = "TAG";

    private static final Set<String> KEYWORDS = Set.of(
            "break", "case", "catch", "class", "const", "continue", "debugger",
            "default", "delete", "do", "else", "export", "extends", "false",
            "finally", "for", "function", "if", "import", "in", "instanceof",
            "new", "null", "return", "super", "switch", "this", "throw", "true",
            "try", "typeof", "var", "void", "while", "with"
    );

    private static final Set<String> CONTEXTUAL_KEYWORDS = Set.of(
            "abstract", "as", "async", "await", "declare", "enum", "from", "get",
            "implements", "infer", "interface", "is", "keyof", "let", "namespace",
            "of", "package", "private", "protected", "public", "readonly",
            "require", "satisfies", "set", "static", "type", "yield"
    );

    private static final Set<String> BUILTIN_TYPES = Set.of(
            "any", "boolean", "never", "number", "object", "string", "symbol",
            "undefined", "unknown", "bigint"
    );

    private static final Set<String> TYPE_CONTEXT_KEYWORDS = Set.of(
            "class", "interface", "extends", "implements", "new", "instanceof", "type"
    );

    @Override
    public boolean supportsIncremental() {
        return false;
    }

    @Override
    public synchronized java.util.Collection<Token> tokenize(
            String text, TokenClassifierCodeEditorProvider classifier) {
        String src = text == null ? "" : text;
        List<LexToken> raw = lex(src, 0, src.length());
        return classify(src, raw, classifier);
    }

    List<Token> tokenizeRange(String text, int from, int to, TokenClassifierCodeEditorProvider classifier) {
        List<LexToken> raw = lex(text, from, to);
        return classify(text, raw, classifier);
    }

    private static List<LexToken> lex(String text, int from, int to) {
        ArrayList<LexToken> tokens = new ArrayList<>(Math.max(16, (to - from) / 4));
        int i = from;

        while (i < to) {
            char c = text.charAt(i);

            if (c == '\r' || c == '\n') {
                int start = i;
                i++;
                if (c == '\r' && i < to && text.charAt(i) == '\n') {
                    i++;
                }
                tokens.add(new LexToken(start, i, TokenType.NEWLINE, text));
                continue;
            }

            if (isInlineWhitespace(c)) {
                int start = i;
                while (i < to && isInlineWhitespace(text.charAt(i))) {
                    i++;
                }
                tokens.add(new LexToken(start, i, TokenType.WHITESPACE, text));
                continue;
            }

            if (c == '/' && i + 1 < to && text.charAt(i + 1) == '/') {
                int start = i;
                i += 2;
                while (i < to && text.charAt(i) != '\r' && text.charAt(i) != '\n') {
                    i++;
                }
                tokens.add(new LexToken(start, i, TokenType.COMMENT, text));
                continue;
            }

            if (c == '/' && i + 1 < to && text.charAt(i + 1) == '*') {
                int start = i;
                int end = text.indexOf("*/", i + 2);
                i = end < 0 || end >= to ? to : end + 2;
                tokens.add(new LexToken(start, i, TokenType.COMMENT, text));
                continue;
            }

            if (c == '"' || c == '\'') {
                int start = i;
                i = scanString(text, i, c, to);
                tokens.add(new LexToken(start, i, TokenType.STRING, text));
                continue;
            }

            if (c == '`') {
                i = lexTemplateLiteral(text, i, to, tokens);
                continue;
            }

            if (c == '@') {
                int start = i;
                i++;
                while (i < to && isIdentPart(text.charAt(i))) {
                    i++;
                }
                tokens.add(new LexToken(start, i, TOKEN_DECORATOR, text));
                continue;
            }

            if (isDigit(c) || (c == '.' && i + 1 < to && isDigit(text.charAt(i + 1)))) {
                int start = i;
                i = scanNumber(text, i, to);
                tokens.add(new LexToken(start, i, TokenType.NUMBER, text));
                continue;
            }

            if (isIdentStart(c)) {
                int start = i;
                while (i < to && isIdentPart(text.charAt(i))) {
                    i++;
                }
                String word = text.substring(start, i);
                String type = KEYWORDS.contains(word) ? TokenType.KEYWORD : TokenType.IDENTIFIER;
                tokens.add(new LexToken(start, i, type, text));
                continue;
            }

            tokens.add(new LexToken(i, i + 1, TokenType.SYMBOL, text));
            i++;
        }

        return tokens;
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

    /**
     * Lexes a template literal starting at the opening backtick, emitting separate
     * tokens so it renders like VS Code: STRING for the literal text (and backticks),
     * SYMBOL for the {@code ${} and} {@code }} delimiters, and regular code tokens
     * for the expression inside each {@code ${...}} interpolation.
     *
     * @return the index just past the template literal.
     */
    private static int lexTemplateLiteral(String text, int start, int to, List<LexToken> tokens) {
        int i = start + 1;
        int textStart = start;
        while (i < to) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < to) {
                i += 2;
                continue;
            }
            if (c == '`') {
                i++;
                tokens.add(new LexToken(textStart, i, TokenType.STRING, text));
                return i;
            }
            if (c == '$' && i + 1 < to && text.charAt(i + 1) == '{') {
                if (i > textStart) {
                    tokens.add(new LexToken(textStart, i, TokenType.STRING, text));
                }
                tokens.add(new LexToken(i, i + 2, TokenType.SYMBOL, text));
                int exprStart = i + 2;
                int exprEnd = findMatchingBrace(text, exprStart, to);
                tokens.addAll(lex(text, exprStart, exprEnd));
                if (exprEnd < to && text.charAt(exprEnd) == '}') {
                    tokens.add(new LexToken(exprEnd, exprEnd + 1, TokenType.SYMBOL, text));
                    i = exprEnd + 1;
                } else {
                    i = exprEnd;
                }
                textStart = i;
                continue;
            }
            i++;
        }
        if (i > textStart) {
            tokens.add(new LexToken(textStart, i, TokenType.STRING, text));
        }
        return i;
    }

    /**
     * Finds the index of the {@code }} that closes an interpolation opened by
     * {@code ${}, starting scanning at} {@code i} with brace depth 1. Braces inside
     * strings, comments and nested template literals are ignored. Returns {@code to}
     * if unterminated.
     */
    private static int findMatchingBrace(String text, int i, int to) {
        int depth = 1;
        while (i < to) {
            char c = text.charAt(i);
            if (c == '`') {
                i = skipTemplateLiteral(text, i, to);
                continue;
            }
            if (c == '"' || c == '\'') {
                i = scanString(text, i, c, to);
                continue;
            }
            if (c == '/' && i + 1 < to && text.charAt(i + 1) == '/') {
                i += 2;
                while (i < to && text.charAt(i) != '\r' && text.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            if (c == '/' && i + 1 < to && text.charAt(i + 1) == '*') {
                int end = text.indexOf("*/", i + 2);
                i = end < 0 || end >= to ? to : end + 2;
                continue;
            }
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
            i++;
        }
        return i;
    }

    /**
     * Skips over a nested template literal (starting at its opening backtick),
     * correctly stepping over its own interpolations. Returns the index just past it.
     */
    private static int skipTemplateLiteral(String text, int start, int to) {
        int i = start + 1;
        while (i < to) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < to) {
                i += 2;
                continue;
            }
            if (c == '`') {
                return i + 1;
            }
            if (c == '$' && i + 1 < to && text.charAt(i + 1) == '{') {
                int exprEnd = findMatchingBrace(text, i + 2, to);
                i = exprEnd < to && text.charAt(exprEnd) == '}' ? exprEnd + 1 : exprEnd;
                continue;
            }
            i++;
        }
        return i;
    }

    private static int scanNumber(String text, int start, int to) {
        int i = start;
        if (text.charAt(i) == '0' && i + 1 < to
                && "xXbBoO".indexOf(text.charAt(i + 1)) >= 0) {
            i += 2;
            while (i < to && (isHex(text.charAt(i)) || text.charAt(i) == '_')) {
                i++;
            }
        } else {
            while (i < to && (isDigit(text.charAt(i)) || text.charAt(i) == '_')) {
                i++;
            }
            if (i < to && text.charAt(i) == '.' && i + 1 < to && isDigit(text.charAt(i + 1))) {
                i++;
                while (i < to && (isDigit(text.charAt(i)) || text.charAt(i) == '_')) {
                    i++;
                }
            }
            if (i < to && (text.charAt(i) == 'e' || text.charAt(i) == 'E')) {
                i++;
                if (i < to && (text.charAt(i) == '+' || text.charAt(i) == '-')) {
                    i++;
                }
                while (i < to && isDigit(text.charAt(i))) {
                    i++;
                }
            }
        }
        if (i < to && text.charAt(i) == 'n') {
            i++;
        }
        return i;
    }

    private static List<Token> classify(String text, List<LexToken> raw,
                                        TokenClassifierCodeEditorProvider classifier) {
        ArrayList<Token> result = new ArrayList<>(raw.size());
        for (int i = 0; i < raw.size(); i++) {
            LexToken token = raw.get(i);
            String type = token.type;
            if (TokenType.IDENTIFIER.equals(token.type)) {
                type = CONTEXTUAL_KEYWORDS.contains(token.text())
                        ? TokenType.KEYWORD
                        : classifyIdentifier(raw, i, classifier);
            }
            result.add(new Token(token.start, token.end, type, text.substring(token.start, token.end)));
        }
        return result;
    }

    private static String classifyIdentifier(List<LexToken> raw, int index,
                                              TokenClassifierCodeEditorProvider classifier) {
        LexToken token = raw.get(index);
        String word = token.text();

        if (BUILTIN_TYPES.contains(word)) {
            return TOKEN_TYPE;
        }
        if (isAllCapsConstant(word)) {
            return TOKEN_CONSTANT;
        }
        if (isTagContext(raw, index)) {
            return TOKEN_TAG;
        }
        if (isMemberFunction(raw, index)) {
            return TOKEN_FUNCTION;
        }
        if (isPropertyAccess(raw, index)) {
            return TOKEN_PROPERTY;
        }
        if (isTypeContext(raw, index)) {
            return TOKEN_CLASS;
        }
        if (isFunctionIdentifier(raw, index)) {
            return TOKEN_FUNCTION;
        }
        if (looksLikeTypeName(word)) {
            return TOKEN_CLASS;
        }

        String classified = classifier == null ? null : classifier.classify(word);
        if (classified != null && !classified.isBlank() && !TokenType.UNKNOWN.equals(classified)) {
            return classified;
        }
        return TOKEN_VARIABLE;
    }

    private static boolean isTagContext(List<LexToken> raw, int index) {
        int prev = previousSignificant(raw, index);
        if (prev < 0) {
            return false;
        }
        String prevText = raw.get(prev).text();
        return ("<".equals(prevText) || "</".equals(prevText))
                && Character.isUpperCase(raw.get(index).text().charAt(0));
    }

    private static boolean isPropertyAccess(List<LexToken> raw, int index) {
        int prev = previousSignificant(raw, index);
        return prev >= 0 && ".".equals(raw.get(prev).text());
    }

    private static boolean isMemberFunction(List<LexToken> raw, int index) {
        return isPropertyAccess(raw, index) && isFunctionIdentifier(raw, index);
    }

    private static boolean isTypeContext(List<LexToken> raw, int index) {
        int prev = previousSignificant(raw, index);
        if (prev < 0) {
            return false;
        }
        LexToken previous = raw.get(prev);
        if (TokenType.KEYWORD.equals(previous.type) && TYPE_CONTEXT_KEYWORDS.contains(previous.text())) {
            return true;
        }
        return ":".equals(previous.text()) || "<".equals(previous.text());
    }

    private static boolean isFunctionIdentifier(List<LexToken> raw, int index) {
        int next = nextSignificant(raw, index);
        return next >= 0 && "(".equals(raw.get(next).text());
    }

    private static int nextSignificant(List<LexToken> raw, int index) {
        for (int i = index + 1; i < raw.size(); i++) {
            if (!isTrivia(raw.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private static int previousSignificant(List<LexToken> raw, int index) {
        for (int i = index - 1; i >= 0; i--) {
            if (!isTrivia(raw.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isTrivia(LexToken token) {
        return TokenType.WHITESPACE.equals(token.type)
                || TokenType.NEWLINE.equals(token.type)
                || TokenType.COMMENT.equals(token.type);
    }

    private static boolean isAllCapsConstant(String value) {
        boolean hasLetter = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c >= 'a' && c <= 'z') {
                return false;
            }
            if (c >= 'A' && c <= 'Z') {
                hasLetter = true;
            }
        }
        return hasLetter && value.length() > 1;
    }

    private static boolean looksLikeTypeName(String value) {
        return !value.isEmpty()
                && Character.isUpperCase(value.charAt(0))
                && !isAllCapsConstant(value);
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isHex(char c) {
        return isDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private static boolean isInlineWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\f' || c == 0x0b;
    }

    private static boolean isIdentStart(char c) {
        return c == '_' || c == '$' || Character.isLetter(c);
    }

    private static boolean isIdentPart(char c) {
        return c == '_' || c == '$' || Character.isLetterOrDigit(c);
    }

    private record LexToken(int start, int end, String type, String source) {
        String text() {
            return source.substring(start, end);
        }
    }
}
