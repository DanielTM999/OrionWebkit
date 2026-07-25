package dtm.ide.lsp;

import dtm.stools.component.panels.editor.code.autocomplete.AutoCompleteItem;

import java.util.List;
import java.util.Locale;


public final class JsTsSnippets {
    private static final List<AutoCompleteItem> ALL = List.of(
            snippet("cl", "console.log", "console.log(${1:value});$0"),
            snippet("ce", "console.error", "console.error(${1:error});$0"),
            snippet("cw", "console.warn", "console.warn(${1:value});$0"),
            snippet("cr", "console.error", "console.error(${1:error});$0"),
            snippet("req", "const require", "const ${1:module} = require('${2:package}');$0"),
            snippet("fun", "function", "function ${1:name}(${2:params}) {\n\t${0}\n}"),
            snippet("af", "async function", "async function ${1:name}(${2:params}) {\n\t${0}\n}"),
            snippet("arr", "arrow function", "const ${1:name} = (${2:params}) => {\n\t${0}\n};"),
            snippet("if", "if", "if (${1:condition}) {\n\t${0}\n}"),
            snippet("ife", "if else", "if (${1:condition}) {\n\t${2}\n} else {\n\t${0}\n}"),
            snippet("for", "for loop", "for (let ${1:i} = 0; ${1:i} < ${2:length}; ${1:i}++) {\n\t${0}\n}"),
            snippet("fof", "for of", "for (const ${1:item} of ${2:items}) {\n\t${0}\n}"),
            snippet("try", "try catch", "try {\n\t${1}\n} catch (${2:error}) {\n\t${0}\n}"),
            snippet("imp", "import named", "import { ${1:name} } from '${2:module}';$0"),
            snippet("imd", "import default", "import ${1:name} from '${2:module}';$0"),
            snippet("exp", "export default", "export default ${1:name};$0")
    );

    private JsTsSnippets() { }

    public static List<AutoCompleteItem> matching(String prefix) {
        String query = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        return ALL.stream().filter(item -> query.isEmpty()
                || item.label().toLowerCase(Locale.ROOT).startsWith(query)
                || item.detail().toLowerCase(Locale.ROOT).startsWith(query)).toList();
    }

    public static boolean isExactTrigger(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return false;
        }
        return ALL.stream().anyMatch(item -> item.label().equalsIgnoreCase(prefix));
    }

    private static AutoCompleteItem snippet(String trigger, String description, String body) {
        return new AutoCompleteItem(body, trigger, description, "Snippet JavaScript/TypeScript", null,
                AutoCompleteItem.Kind.SNIPPET, List.of());
    }
}
