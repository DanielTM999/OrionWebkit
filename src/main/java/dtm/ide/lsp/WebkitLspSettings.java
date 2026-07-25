package dtm.ide.lsp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;










final class WebkitLspSettings {

    private WebkitLspSettings() {
    }

    private static final Map<String, Object> HTML = Map.of(
            "suggest", Map.of("html5", true),
            "validate", Map.of("scripts", true, "styles", true),
            "autoClosingTags", true,
            "format", Map.of("enable", true)
    );

    private static final Map<String, Object> CSS_LIKE = Map.of(
            "validate", true,
            "completion", Map.of(
                    "triggerPropertyValueCompletion", true,
                    "completePropertyWithSemicolon", true
            ),
            "lint", Map.of("compatibleVendorPrefixes", "ignore", "vendorPrefix", "ignore")
    );

    private static final Map<String, Object> JS_TS = Map.of(
            "suggest", Map.of(
                    "completeFunctionCalls", true,
                    "includeAutomaticOptionalChainCompletions", true,
                    "includeCompletionsForImportStatements", true,
                    "autoImports", true
            ),
            "preferences", Map.of(
                    "importModuleSpecifier", "shortest",
                    "quoteStyle", "auto"
            ),
            "format", Map.of("enable", true)
    );

    



    static Object forSection(String section) {
        if (section == null || section.isBlank()) {
            return Map.of();
        }
        String root = section.toLowerCase(Locale.ROOT).split("\\.")[0];
        return switch (root) {
            case "html" -> HTML;
            case "css", "scss", "less", "sass" -> CSS_LIKE;
            case "javascript", "typescript", "js/ts" -> JS_TS;
            default -> Map.of();
        };
    }

    






    static Map<String, Object> initializationOptions(LspServerKind kind, String typescriptLibDir) {
        Map<String, Object> options = new LinkedHashMap<>();
        switch (kind) {
            case HTML -> {
                options.put("embeddedLanguages", Map.of("css", true, "javascript", true));
                options.put("configurationSection", List.of("html", "css", "javascript"));
                options.put("provideFormatter", true);
                options.put("handledSchemas", List.of("file"));
            }
            case CSS -> {
                options.put("provideFormatter", true);
                options.put("handledSchemas", List.of("file"));
            }
            case TYPESCRIPT -> {
                options.put("hostInfo", "orion-webkit");
                if (typescriptLibDir != null) {
                    options.put("tsserver", Map.of("path", typescriptLibDir));
                }
                options.put("preferences", Map.of(
                        "includeCompletionsForModuleExports", true,
                        "includeCompletionsForImportStatements", true,
                        "includeCompletionsWithSnippetText", true,
                        "includeCompletionsWithInsertText", true,
                        "includeAutomaticOptionalChainCompletions", true,
                        "importModuleSpecifierPreference", "shortest"
                ));
            }
        }
        return options;
    }
}
