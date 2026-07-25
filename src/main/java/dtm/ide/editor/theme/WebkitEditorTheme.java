package dtm.ide.editor.theme;

import dtm.ide.api.theme.EditorTheme;
import dtm.ide.api.theme.EditorThemeConfig;
import dtm.ide.editor.tokenizer.CssTokenizerProvider;
import dtm.ide.editor.tokenizer.HtmlTokenizerProvider;
import dtm.ide.editor.tokenizer.JsTokenizerProvider;
import dtm.stools.component.panels.editor.code.prototype.Token;
import dtm.stools.component.panels.editor.code.prototype.constants.TokenType;

import java.awt.Color;
import java.util.Locale;
import java.util.Set;

public final class WebkitEditorTheme implements EditorTheme {

    private static final Set<String> WEBKIT_FILE_TYPES = Set.of(
            "html", "htm", "vue", "svelte",
            "css", "scss", "sass", "less",
            "js", "jsx", "mjs", "cjs", "ts", "tsx"
    );

    @Override
    public EditorThemeConfig getConfigByFileType(String fileType) {
        if (fileType == null || fileType.isBlank()) {
            return this;
        }

        String normalized = fileType.toLowerCase(Locale.ROOT);
        if (normalized.startsWith(".")) {
            normalized = normalized.substring(1);
        }

        return WEBKIT_FILE_TYPES.contains(normalized) ? this : null;
    }

    @Override
    public Color getColorByToken(Token token) {
        if (token == null) {
            return null;
        }
        return getColorByToken(token.getType());
    }

    @Override
    public Color getColorByToken(String tokenType) {
        if (tokenType == null) {
            return null;
        }

        return switch (tokenType) {
            case JsTokenizerProvider.TOKEN_CLASS, JsTokenizerProvider.TOKEN_TYPE ->
                    new Color(78, 201, 176);
            case JsTokenizerProvider.TOKEN_VARIABLE ->
                    new Color(156, 220, 254);
            case JsTokenizerProvider.TOKEN_CONSTANT ->
                    new Color(181, 206, 168);
            case JsTokenizerProvider.TOKEN_FUNCTION ->
                    new Color(220, 220, 170);
            case JsTokenizerProvider.TOKEN_PROPERTY ->
                    new Color(156, 220, 180);
            case JsTokenizerProvider.TOKEN_DECORATOR ->
                    new Color(220, 220, 170);
            case JsTokenizerProvider.TOKEN_TAG, HtmlTokenizerProvider.TOKEN_TAG ->
                    new Color(86, 156, 214);
            case HtmlTokenizerProvider.TOKEN_ATTRIBUTE ->
                    new Color(156, 220, 254);
            case HtmlTokenizerProvider.TOKEN_ENTITY ->
                    new Color(215, 186, 125);
            case HtmlTokenizerProvider.TOKEN_DOCTYPE ->
                    new Color(155, 155, 155);
            case CssTokenizerProvider.TOKEN_SELECTOR ->
                    new Color(215, 186, 125);
            case CssTokenizerProvider.TOKEN_PROPERTY ->
                    new Color(156, 220, 254);
            case CssTokenizerProvider.TOKEN_ATRULE ->
                    new Color(197, 134, 192);
            case CssTokenizerProvider.TOKEN_HEXCOLOR ->
                    new Color(181, 206, 168);
            case CssTokenizerProvider.TOKEN_UNIT ->
                    new Color(181, 206, 168);
            case CssTokenizerProvider.TOKEN_VARIABLE ->
                    new Color(78, 201, 176);
            case TokenType.KEYWORD ->
                    new Color(86, 156, 214);
            case TokenType.STRING ->
                    new Color(206, 145, 120);
            case TokenType.NUMBER ->
                    new Color(181, 206, 168);
            case TokenType.COMMENT ->
                    new Color(106, 153, 85);
            default ->
                    null;
        };
    }
}
