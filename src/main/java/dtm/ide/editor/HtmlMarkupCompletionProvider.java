package dtm.ide.editor;

import dtm.stools.component.panels.editor.code.autocomplete.AutoCompleteItem;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Local HTML suggestions available immediately, including while the LSP is starting. */
public final class HtmlMarkupCompletionProvider {
    private static final List<String> TAGS = List.of("a", "article", "aside", "audio", "body", "button", "canvas", "div", "form", "footer", "h1", "h2", "h3", "head", "header", "html", "img", "input", "label", "li", "link", "main", "meta", "nav", "ol", "option", "p", "script", "section", "select", "span", "style", "table", "tbody", "td", "textarea", "title", "tr", "ul", "video");
    private static final List<String> ATTRIBUTES = List.of("id", "class", "style", "title", "hidden", "lang", "role", "href", "target", "rel", "src", "alt", "width", "height", "loading", "type", "name", "value", "placeholder", "required", "disabled", "checked", "selected", "autocomplete", "for", "action", "method", "charset", "content", "http-equiv", "defer", "async", "onclick", "onchange", "oninput");
    private static final Set<String> VOID_TAGS = Set.of("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "source", "track", "wbr");

    public List<AutoCompleteItem> suggestions(String text, int caretOffset) {
        return suggestions(text, caretOffset, false);
    }

    public List<AutoCompleteItem> suggestions(String text, int caretOffset, boolean explicit) {
        if (text == null || text.isEmpty()) return List.of();
        int caret = Math.max(0, Math.min(caretOffset, text.length()));
        int open = text.lastIndexOf('<', Math.max(0, caret - 1));
        int closed = text.lastIndexOf('>', Math.max(0, caret - 1));
        if (open > closed) {
            return insideTag(text, open, caret);
        }
        String plainPrefix = plainTagPrefix(text, caret);
        if (!plainPrefix.isEmpty()) {
            return tagItems(plainPrefix, true);
        }
        return explicit ? allTagItemsWithCloseSuggestion(text, caret) : closingTagSuggestion(text, caret);
    }

    private List<AutoCompleteItem> insideTag(String text, int open, int caret) {
        String fragment = text.substring(open + 1, caret);
        if (fragment.startsWith("/")) return closeItems(openTags(text, caret), fragment.substring(1).toLowerCase(Locale.ROOT));
        if (fragment.isBlank() || isTagName(fragment)) {
            return tagItems(fragment.toLowerCase(Locale.ROOT), false);
        }
        int space = Math.max(fragment.lastIndexOf(' '), fragment.lastIndexOf('\n'));
        String prefix = space < 0 ? "" : fragment.substring(space + 1).toLowerCase(Locale.ROOT);
        if (prefix.contains("=") || prefix.startsWith("\"") || prefix.startsWith("'")) return List.of();
        List<AutoCompleteItem> items = new ArrayList<>();
        for (String attribute : ATTRIBUTES) if (attribute.startsWith(prefix)) items.add(new AutoCompleteItem(attribute + "=\"\"", attribute, "HTML attribute"));
        return items;
    }

    private List<AutoCompleteItem> closingTagSuggestion(String text, int caret) {
        Deque<String> tags = openTags(text, caret);
        if (tags.isEmpty()) return List.of();
        String tag = tags.peek();
        return List.of(new AutoCompleteItem("</" + tag + ">", "</" + tag + ">", "Close " + tag + " tag"));
    }

    private List<AutoCompleteItem> allTagItemsWithCloseSuggestion(String text, int caret) {
        List<AutoCompleteItem> items = new ArrayList<>();
        Deque<String> tags = openTags(text, caret);
        if (!tags.isEmpty()) {
            String tag = tags.peek();
            items.add(new AutoCompleteItem("</" + tag + ">", "</" + tag + ">", "Close " + tag + " tag"));
        }
        for (String tag : TAGS) {
            items.add(tagItem(tag, true));
        }
        return items;
    }

    private static List<AutoCompleteItem> tagItems(String prefix, boolean includeOpeningBracket) {
        List<AutoCompleteItem> items = new ArrayList<>();
        for (String tag : TAGS) {
            if (tag.startsWith(prefix)) {
                items.add(tagItem(tag, includeOpeningBracket));
            }
        }
        return items;
    }

    private static AutoCompleteItem tagItem(String tag, boolean includeOpeningBracket) {
        String prefix = includeOpeningBracket ? "<" : "";
        String insert = switch (tag) {
            case "a" -> prefix + "a href=\"\"></a>";
            case "img" -> prefix + "img src=\"\" alt=\"\">";
            case "link" -> prefix + "link rel=\"stylesheet\" href=\"\">";
            case "script" -> prefix + "script src=\"\"></script>";
            case "form" -> prefix + "form action=\"\" method=\"post\"></form>";
            case "input" -> prefix + "input type=\"text\" name=\"\">";
            case "button" -> prefix + "button type=\"button\"></button>";
            default -> prefix + tag + "></" + tag + ">";
        };
        return new AutoCompleteItem(insert, tag, "HTML element");
    }

    private static List<AutoCompleteItem> closeItems(Deque<String> tags, String prefix) {
        List<AutoCompleteItem> items = new ArrayList<>();
        for (String tag : tags) if (tag.startsWith(prefix)) items.add(new AutoCompleteItem(tag + ">", tag, "Close " + tag + " tag"));
        return items;
    }

    private static Deque<String> openTags(String text, int end) {
        Deque<String> stack = new ArrayDeque<>();
        for (int position = 0; position < end;) {
            int open = text.indexOf('<', position);
            if (open < 0 || open >= end) break;
            int close = text.indexOf('>', open + 1);
            if (close < 0 || close >= end) break;
            String content = text.substring(open + 1, close).trim();
            position = close + 1;
            if (content.isEmpty() || content.startsWith("!") || content.startsWith("?")) continue;
            boolean closing = content.startsWith("/");
            String name = tagName(closing ? content.substring(1) : content);
            if (name.isEmpty()) continue;
            if (closing) while (!stack.isEmpty() && !stack.pop().equals(name)) { }
            else if (!content.endsWith("/") && !VOID_TAGS.contains(name)) stack.push(name);
        }
        return stack;
    }

    private static String tagName(String content) {
        int end = 0;
        while (end < content.length() && (Character.isLetterOrDigit(content.charAt(end)) || content.charAt(end) == '-')) end++;
        return content.substring(0, end).toLowerCase(Locale.ROOT);
    }

    private static boolean isTagName(String value) {
        for (int i = 0; i < value.length(); i++) if (!Character.isLetterOrDigit(value.charAt(i)) && value.charAt(i) != '-') return false;
        return true;
    }

    private static String plainTagPrefix(String text, int caret) {
        int start = Math.max(text.lastIndexOf('\n', Math.max(0, caret - 1)) + 1,
                text.lastIndexOf('\r', Math.max(0, caret - 1)) + 1);
        String linePrefix = text.substring(start, caret).trim();
        return isTagName(linePrefix) ? linePrefix.toLowerCase(Locale.ROOT) : "";
    }
}
