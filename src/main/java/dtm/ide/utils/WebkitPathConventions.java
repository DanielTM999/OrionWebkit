package dtm.ide.utils;

import dtm.stools.component.panels.editor.code.prototype.folding.FoldRule;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class WebkitPathConventions {
    private WebkitPathConventions() {}

    private static final Set<String> HTML_EXTENSIONS = Set.of(
            "html",
            "htm",
            "vue",
            "svelte"
    );

    private static final Set<String> CSS_EXTENSIONS = Set.of(
            "css",
            "scss",
            "sass",
            "less"
    );

    private static final Set<String> JS_EXTENSIONS = Set.of(
            "js",
            "jsx",
            "mjs",
            "cjs",
            "ts",
            "tsx"
    );

    private static final Set<String> FRONTEND_EXTENSIONS = union(HTML_EXTENSIONS, CSS_EXTENSIONS, JS_EXTENSIONS);


    public static boolean isWebkitPath(Path path) {
        return FRONTEND_EXTENSIONS.contains(extensionOf(path));
    }

    public static boolean isHtmlLike(Path path) {
        return HTML_EXTENSIONS.contains(extensionOf(path));
    }

    public static boolean isCssLike(Path path) {
        return CSS_EXTENSIONS.contains(extensionOf(path));
    }

    public static boolean isJsLike(Path path) {
        return JS_EXTENSIONS.contains(extensionOf(path));
    }

    public static boolean isHighlightable(Path path) {
        return isHtmlLike(path) || isCssLike(path) || isJsLike(path);
    }

    public static Collection<FoldRule> foldRules(Path path) {
        if (isHtmlLike(path)) {
            return List.of(
                    FoldRule.htmlTags(),
                    FoldRule.pair('{', '}'),
                    FoldRule.pair("/*", "*/")
            );
        }
        if (isCssLike(path)) {
            return List.of(
                    FoldRule.pair('{', '}'),
                    FoldRule.pair("/*", "*/")
            );
        }
        if (isJsLike(path)) {
            return List.of(
                    FoldRule.pair('{', '}'),
                    FoldRule.pair('[', ']'),
                    FoldRule.pair("/*", "*/")
            );
        }
        return null;
    }

    public static Path normalizePath(Path path) {
        return path == null ? null : path.toAbsolutePath().normalize();
    }

    public static boolean samePath(Path a, Path b) {
        return a != null && b != null && normalizePath(a).equals(normalizePath(b));
    }

    public static String extensionOf(Path path) {
        if (path == null || path.getFileName() == null) {
            return "";
        }

        String fileName = path.getFileName()
                .toString()
                .toLowerCase(Locale.ROOT);

        int extensionIndex = fileName.lastIndexOf('.');

        if (extensionIndex < 0 || extensionIndex == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(extensionIndex + 1);
    }

    @SafeVarargs
    private static Set<String> union(Set<String>... sets) {
        Set<String> result = new java.util.HashSet<>();
        for (Set<String> set : sets) {
            result.addAll(set);
        }
        return Set.copyOf(result);
    }

}
