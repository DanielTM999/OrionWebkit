package dtm.ide;


import dtm.di.annotations.Async;
import dtm.di.annotations.Singleton;
import dtm.ide.api.annotations.CodeFormatProprity;
import dtm.ide.api.annotations.CooperativeAdapter;
import dtm.ide.api.annotations.PluginReference;
import dtm.ide.api.context.IdeProjectContext;
import dtm.ide.api.extension.IdeAdapter;
import dtm.ide.api.extension.menu.IdeMenuBuilder;
import dtm.ide.api.project.editor.DocumentHighlight;
import dtm.ide.api.project.editor.FormatCodeContext;
import dtm.ide.api.project.editor.IdeCompletionContext;
import dtm.ide.api.project.editor.IdeCompletionTriggerKind;
import dtm.ide.api.project.editor.IdeDiagnosticsContext;
import dtm.ide.api.project.editor.IdeDocumentHighlightContext;
import dtm.ide.api.project.editor.IdeDefinitionContext;
import dtm.ide.api.project.editor.IdeHoverContext;
import dtm.ide.api.project.editor.IdeFormatScope;
import dtm.ide.api.project.editor.IdeSignatureHelpContext;
import dtm.ide.api.project.editor.IdeRenameContext;
import dtm.ide.api.project.editor.IdeCodeActionContext;
import dtm.ide.api.project.editor.IdeEditorContext;
import dtm.ide.api.project.editor.IdeWordClickContext;
import dtm.ide.api.theme.EditorTheme;
import dtm.ide.editor.HtmlMarkupCompletionProvider;
import dtm.ide.editor.theme.WebkitEditorTheme;
import dtm.ide.lsp.WebkitLspService;
import dtm.ide.lsp.JsTsSnippets;
import dtm.ide.navigation.JavaScriptNavigationIndex;
import dtm.ide.navigation.WebkitNavigationPopup;
import dtm.ide.ui.NewWebItemPanel;
import dtm.ide.utils.WebkitPathConventions;
import dtm.request_actions.http.download.core.DownloadObserver;
import dtm.stools.component.panels.editor.code.autocomplete.AutoCompleteItem;
import dtm.stools.component.panels.editor.code.diagnostics.Diagnostic;
import dtm.stools.component.panels.editor.code.api.Location;
import dtm.stools.component.panels.editor.code.api.Range;
import dtm.stools.component.panels.editor.code.api.TextEdit;
import dtm.stools.component.panels.editor.code.api.CodeAction;
import dtm.stools.component.panels.editor.code.hover.HoverInfo;
import dtm.stools.component.panels.editor.code.signature.SignatureHelp;
import dtm.stools.component.panels.editor.code.prototype.folding.FoldRule;
import dtm.stools.component.panels.editor.code.provider.TokenizerCodeEditorProvider;
import dtm.stools.utils.ImageUtils;
import lombok.extern.slf4j.Slf4j;
import javax.swing.Icon;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

@Slf4j
@Singleton
@CooperativeAdapter
@PluginReference(id = "orion-webkit-adapter")
public class WebkitAdapter extends IdeAdapter {

    private static final Set<Character> DEFAULT_COMPLETION_TRIGGERS =
            Set.of('.', '<', '>', '/', ':', '"', '\'', '-', '@', '$');
    private static final int HTML_CONTEXT_SCAN_WINDOW = 50_000;

    private final WebkitEditorRegistry editorRegistry = new WebkitEditorRegistry();
    private final EditorTheme editorTheme = new WebkitEditorTheme();
    private final HtmlMarkupCompletionProvider htmlCompletionProvider = new HtmlMarkupCompletionProvider();
    private final ExecutorService lspSetupExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "orion-webkit-lsp-setup");
        t.setDaemon(true);
        return t;
    });


    private volatile WebkitLspService lspService;
    private volatile Path projectPath;

    @Override
    public boolean supports(Path path) {
        return true;
    }

    @Override
    public String getProjectType() {
        return "Orion Webkit";
    }

    @Override
    public void onAdapterSelected(IdeProjectContext context) {
        this.projectPath = context == null ? null : context.getProjectPath().orElse(null);
        
    }

    @Override
    public void onProjectClosed(IdeProjectContext context) {
        this.projectPath = null;
        WebkitLspService service = lspService;
        if (service != null) {
            lspSetupExecutor.execute(service::stopAll);
        }
    }

    @Override
    public boolean handlesPath(Path path) {
        return WebkitPathConventions.isWebkitPath(path);
    }

    @Override
    public void contributeProjectTreeMenu(IdeMenuBuilder menu, List<Path> selectedPaths) {
        if (menu == null || selectedPaths == null || selectedPaths.size() != 1) {
            return;
        }
        Path selected = selectedPaths.getFirst();
        if (selected == null || !Files.isDirectory(selected)) {
            return;
        }
        menu.into("tree.new", sub -> sub.item("Web file...", newWebItemIcon(), e -> openNewWebItem(selected)));
    }

    private Icon newWebItemIcon() {
        return ImageUtils.getIconByResource(WebkitAdapter.class, "imgs/webNew.svg")
                .map(icon -> ImageUtils.resizeIcon(icon, 16, 16))
                .orElse(null);
    }

    private void openNewWebItem(Path directory) {
        Runnable showDialog = () -> {
            NewWebItemPanel panel = new NewWebItemPanel();
            NewWebItemPanel.Result result = createModernComponentDialogBuilder(NewWebItemPanel.Result.class)
                    .title("New web file")
                    .draggable(true)
                    .showIcon(false)
                    .accentColor(new Color(59, 130, 246))
                    .confirmText("Create")
                    .cancelText("Cancel")
                    .enterConfirms(true)
                    .component(panel)
                    .result(ctx -> panel.getResult())
                    .show();
            if (result != null) {
                createWebFile(directory, result);
            }
        };
        if (SwingUtilities.isEventDispatchThread()) {
            showDialog.run();
        } else {
            SwingUtilities.invokeLater(showDialog);
        }
    }

    private void createWebFile(Path directory, NewWebItemPanel.Result result) {
        String fileName = result.kind().fileName(result.name());
        Path file = directory.resolve(fileName);
        if (Files.exists(file)) {
            setStatusBarText("Already exists " + file.getFileName());
            requestOpenFile(file);
            return;
        }
        try {
            Files.writeString(file, result.kind().template(result.name()), StandardCharsets.UTF_8);
            requestProjectTreeViewRefresh();
            requestOpenFile(file);
            setStatusBarText("Created " + file.getFileName());
        } catch (Exception e) {
            setStatusBarText("Failed to create web file: " + e.getMessage());
            log.warn("Failed to create web file {}", file, e);
        }
    }

    @Override
    public EditorTheme getEditorTheme() {
        return editorTheme;
    }

    @Override
    public TokenizerCodeEditorProvider resolveSyntaxHighlightTokenizer(Path filePath) {
        if (filePath == null || !WebkitPathConventions.isHighlightable(filePath)) {
            return null;
        }
        return editorRegistry.tokenizerFor(filePath);
    }

    @Override
    public Collection<FoldRule> resolveFoldRules(Path filePath) {
        return WebkitPathConventions.foldRules(filePath);
    }

    @Override
    public void configureEditor(IdeEditorContext context) {
        if (context == null || context.filePath() == null || !WebkitPathConventions.isHighlightable(context.filePath())) {
            return;
        }
        Path normalized = WebkitPathConventions.normalizePath(context.filePath());
        editorRegistry.trackEditor(normalized, context);
        applyInitialSyntaxHighlight(context);
    }

    @Override
    public void onEditorOpen(IdeEditorContext editorContext) {
        if (editorContext == null || editorContext.filePath() == null
                || !WebkitPathConventions.isHighlightable(editorContext.filePath())) {
            return;
        }
        Path normalized = WebkitPathConventions.normalizePath(editorContext.filePath());
        editorRegistry.trackEditor(normalized, editorContext);
        applyInitialSyntaxHighlight(editorContext);
        startLanguageServerFor(normalized, editorContext.getText());
    }

    @Override
    public void onEditorClose(Path filePath) {
        if (filePath == null || !WebkitPathConventions.isHighlightable(filePath)) {
            return;
        }
        Path normalized = WebkitPathConventions.normalizePath(filePath);
        editorRegistry.close(normalized);
        WebkitLspService service = lspService;
        if (service != null) {
            lspSetupExecutor.execute(() -> service.closeDocument(normalized));
        }
    }

    @Override
    public boolean supportsIncrementalDiagnostics() {
        return false;
    }

    @Override
    public Collection<Diagnostic> getDiagnostics(IdeDiagnosticsContext context, boolean incremental, Collection<Diagnostic> previous) {
        if (context == null || context.getFilePath() == null || !WebkitPathConventions.isHighlightable(context.getFilePath())) {
            return Collections.emptyList();
        }
        WebkitLspService service = lspService;
        if (service == null) {
            return Collections.emptyList();
        }
        Path file = WebkitPathConventions.normalizePath(context.getFilePath());
        service.sync(file, context.getText());
        return service.diagnostics(file);
    }

    @Override
    public List<AutoCompleteItem> getCompletionSuggestions(IdeCompletionContext context) {
        if (context == null || !WebkitPathConventions.isHighlightable(context.filePath())) {
            return Collections.emptyList();
        }
        String prefix = completionPrefix(context);
        boolean javascriptContext = isJavaScriptCompletionContext(context);
        List<AutoCompleteItem> pathItems = WebkitPathConventions.isHtmlLike(context.filePath())
                ? htmlPathCompletionItems(context)
                : List.of();
        if (insideHtmlAttributeValue(context.text(), context.caretOffset())) {
            // Paths are completed locally and only when explicitly requested. This keeps ./, ../ and
            // the portion already typed intact instead of letting a generic completion replace it.
            return pathItems;
        }
        List<AutoCompleteItem> htmlItems = WebkitPathConventions.isHtmlLike(context.filePath())
                ? htmlCompletionProvider.suggestions(context.text(), context.caretOffset(),
                context.triggerKind() != IdeCompletionTriggerKind.TYPING)
                : List.of();
        List<AutoCompleteItem> snippets = javascriptContext ? JsTsSnippets.matching(prefix) : List.of();
        WebkitLspService service = lspService;
        if (service == null) {
            return mergeCompletionItems(htmlItems, snippets);
        }
        Path file = WebkitPathConventions.normalizePath(context.filePath());
        if (!service.isRunningFor(file)) {
            startLanguageServerFor(file, context.text());
            return mergeCompletionItems(htmlItems, snippets);
        }
        List<AutoCompleteItem> lspItems = service.complete(file, context.text(), context.caretLine(), context.caretCol(), prefix, triggerCharacterOf(context));
        if (!javascriptContext) {
            return mergeCompletionItems(htmlItems, lspItems);
        }
        List<AutoCompleteItem> out = new java.util.ArrayList<>(lspItems.size() + snippets.size() + htmlItems.size());
        out.addAll(htmlItems);
        if (JsTsSnippets.isExactTrigger(prefix)) {
            out.addAll(snippets);
            out.addAll(lspItems);
        } else {
            out.addAll(lspItems);
            out.addAll(snippets);
        }
        return out;
    }

    private static List<AutoCompleteItem> mergeCompletionItems(List<AutoCompleteItem> first,
                                                                 List<AutoCompleteItem> second) {
        if (first.isEmpty()) return second;
        if (second.isEmpty()) return first;
        List<AutoCompleteItem> merged = new java.util.ArrayList<>(first.size() + second.size());
        merged.addAll(first);
        merged.addAll(second);
        return merged;
    }

    private static List<AutoCompleteItem> htmlPathCompletionItems(IdeCompletionContext context) {
        if (context.triggerKind() == IdeCompletionTriggerKind.TYPING) {
            return List.of();
        }
        String typedPath = htmlAttributePathPrefix(context.text(), context.caretOffset());
        Path currentFile = context.filePath();
        if (typedPath == null || currentFile == null || currentFile.getParent() == null) {
            return List.of();
        }
        Path base = currentFile.toAbsolutePath().normalize().getParent();
        String normalized = typedPath.replace('\\', '/');
        if (normalized.matches("^[A-Za-z][A-Za-z0-9+.-]*:.*") || normalized.startsWith("//")) {
            return List.of(); // http:, https:, data: and protocol-relative URLs are not local paths.
        }
        boolean startsFromCurrentDirectory = normalized.isEmpty();
        int slash = normalized.lastIndexOf('/');
        String folderPart = slash < 0 ? "" : normalized.substring(0, slash + 1);
        String namePart = slash < 0 ? normalized : normalized.substring(slash + 1);
        String relativeFolder = folderPart;
        while (relativeFolder.startsWith("./")) {
            relativeFolder = relativeFolder.substring(2);
        }
        Path folder;
        try {
            folder = base.resolve(relativeFolder.replace('/', java.io.File.separatorChar)).normalize();
        } catch (InvalidPathException e) {
            return List.of();
        }
        if (!folder.startsWith(base) || !Files.isDirectory(folder)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(folder)) {
            return files
                    .filter(path -> path.getFileName() != null)
                    .filter(path -> path.getFileName().toString().startsWith(namePart))
                    .sorted()
                    .limit(100)
                    .map(path -> {
                        String suffix = path.getFileName() + (Files.isDirectory(path) ? "/" : "");
                        String candidate = (startsFromCurrentDirectory ? "./" : folderPart) + suffix;
                        // The editor preserves the portion before the current file-name prefix.
                        // Insert only the missing suffix to avoid turning ./index.js into ././index.js.
                        String insert = startsFromCurrentDirectory ? "./" + suffix : suffix;
                        return new AutoCompleteItem(insert, candidate, Files.isDirectory(path) ? "folder" : "file");
                    })
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static boolean isJavaScriptCompletionContext(IdeCompletionContext context) {
        if (WebkitPathConventions.isJsLike(context.filePath())) {
            return true;
        }
        return WebkitPathConventions.isHtmlLike(context.filePath())
                && context.text() != null
                && insideBlock(context.text(), context.caretOffset(), "<script", "</script");
    }

    @Override
    public List<Location> findDefinitions(IdeDefinitionContext context) {
        return locations(context, false);
    }

    @Override
    public List<Location> findReferences(IdeDefinitionContext context) {
        return locations(context, true);
    }

    @Override
    public void onWordClick(IdeWordClickContext context) {
        if (context == null || context.filePath() == null
                || !WebkitPathConventions.isJsLike(context.filePath())) {
            return;
        }
        Thread.ofVirtual().name("orion-webkit-navigation").start(() -> {
            List<Location> currentFileDefinitions = JavaScriptNavigationIndex.findDefinitions(
                    null,
                    context.filePath(),
                    context.text(),
                    context.word()
            );
            boolean declarationClick = JavaScriptNavigationIndex.isDeclarationAt(
                    context.text(),
                    context.word(),
                    context.startOffset()
            ) || currentFileDefinitions.stream().anyMatch(location ->
                    pointsToClick(location, context.filePath(), context.line(), context.col()));

            if (declarationClick) {
                List<Location> references = JavaScriptNavigationIndex.findReferences(
                        projectPath,
                        context.filePath(),
                        context.text(),
                        context.word()
                );
                references = withoutDefinitions(
                        references, currentFileDefinitions, context.filePath());
                if (references.isEmpty()) {
                    setStatusBarText("JavaScript: no usages found");
                    return;
                }
                showUsageTargets(
                        references,
                        context.filePath(),
                        context.text(),
                        context.editorContext(),
                        context.word()
                );
                return;
            }

            if (!currentFileDefinitions.isEmpty()) {
                openDefinitionTargets(currentFileDefinitions, context);
                return;
            }

            IdeDefinitionContext request = new IdeDefinitionContext(
                    context.text(),
                    context.filePath(),
                    context.line(),
                    context.col(),
                    context.startOffset()
            );
            List<Location> definitions = findDefinitions(request);
            if (definitions.isEmpty()) {
                definitions = JavaScriptNavigationIndex.findDefinitions(
                        projectPath,
                        context.filePath(),
                        context.text(),
                        context.word()
                );
            }
            if (definitions.isEmpty()) {
                setStatusBarText("JavaScript: definition not found");
                return;
            }
            openDefinitionTargets(definitions, context);
        });
    }

    private void openDefinitionTargets(List<Location> definitions,
                                       IdeWordClickContext context) {
            if (definitions.size() == 1) {
                openLocation(definitions.getFirst(), context.filePath(), context.editorContext());
                return;
            }
            showDefinitionTargets(
                    definitions,
                    context.filePath(),
                    context.text(),
                    context.editorContext(),
                    context.word()
            );
    }

    private static boolean pointsToClick(Location location, Path sourceFile, int line, int col) {
        if (location == null || location.range() == null || location.range().start() == null) {
            return false;
        }
        Path target = location.isLocal() ? sourceFile : pathFromUri(location.uri());
        if (target == null || sourceFile == null
                || !target.toAbsolutePath().normalize()
                .equals(sourceFile.toAbsolutePath().normalize())) {
            return false;
        }
        return location.range().start().line() == line
                && location.range().start().col() == col;
    }

    private static List<Location> withoutDefinitions(List<Location> references,
                                                     List<Location> definitions,
                                                     Path sourceFile) {
        Set<String> declarationKeys = new HashSet<>();
        if (definitions != null) {
            definitions.stream()
                    .filter(java.util.Objects::nonNull)
                    .map(location -> locationKey(location, sourceFile))
                    .forEach(declarationKeys::add);
        }
        return references.stream()
                .filter(location -> !declarationKeys.contains(locationKey(location, sourceFile)))
                .toList();
    }

    private static String locationKey(Location location, Path sourceFile) {
        if (location == null || location.range() == null || location.range().start() == null) {
            return "";
        }
        Path path = location.isLocal() ? sourceFile : pathFromUri(location.uri());
        String target = path == null
                ? String.valueOf(location.uri())
                : path.toAbsolutePath().normalize().toString().toLowerCase(java.util.Locale.ROOT);
        return target + ':'
                + location.range().start().line() + ':'
                + location.range().start().col();
    }

    private List<Location> locations(IdeDefinitionContext context, boolean references) {
        if (context == null || context.filePath() == null || !WebkitPathConventions.isHighlightable(context.filePath())) return List.of();
        WebkitLspService service = lspService;
        return service == null ? List.of() : service.definitions(WebkitPathConventions.normalizePath(context.filePath()), context.text(), context.line(), context.col(), references);
    }

    private void showDefinitionTargets(List<Location> targets,
                                       Path sourceFile,
                                       String sourceText,
                                       IdeEditorContext sourceEditor,
                                       String symbol) {
        List<WebkitNavigationPopup.Item> items =
                buildNavigationItems(targets, sourceFile, sourceText, sourceEditor);
        if (items.isEmpty()) {
            return;
        }
        String header = items.size() + " definitions"
                + (symbol == null || symbol.isBlank() ? "" : " of " + symbol);
        WebkitNavigationPopup.show(null, null, header, items);
    }

    private void showUsageTargets(List<Location> targets,
                                  Path sourceFile,
                                  String sourceText,
                                  IdeEditorContext sourceEditor,
                                  String symbol) {
        List<WebkitNavigationPopup.Item> items =
                buildNavigationItems(targets, sourceFile, sourceText, sourceEditor);
        if (items.isEmpty()) {
            return;
        }
        String header = items.size() + (items.size() == 1 ? " usage" : " usages")
                + (symbol == null || symbol.isBlank() ? "" : " of " + symbol);
        WebkitNavigationPopup.show(null, null, header, items);
    }

    private List<WebkitNavigationPopup.Item> buildNavigationItems(
            List<Location> locations,
            Path sourceFile,
            String sourceText,
            IdeEditorContext sourceEditor) {
        List<WebkitNavigationPopup.Item> items = new ArrayList<>();
        Map<Path, List<String>> cache = new HashMap<>();
        Path root = projectPath == null ? null : projectPath.toAbsolutePath().normalize();
        for (Location location : locations) {
            if (location == null || location.range() == null
                    || location.range().start() == null) {
                continue;
            }
            Path path = location.isLocal() ? sourceFile : pathFromUri(location.uri());
            if (path == null || !Files.isRegularFile(path)) {
                continue;
            }
            Path normalized = path.toAbsolutePath().normalize();
            int line = location.range().start().line();
            String snippet = sourceLine(normalized, sourceFile, sourceText, line, cache);
            Path shown = root != null && normalized.startsWith(root)
                    ? root.relativize(normalized)
                    : normalized.getFileName();
            String display = (shown == null ? normalized : shown).toString() + ":" + (line + 1);
            boolean currentFile = sourceFile != null
                    && normalized.equals(sourceFile.toAbsolutePath().normalize());
            items.add(new WebkitNavigationPopup.Item(
                    snippet,
                    display,
                    currentFile,
                    () -> openLocation(location, sourceFile, sourceEditor)
            ));
        }
        return List.copyOf(items);
    }

    private void openLocation(Location location, Path sourceFile, IdeEditorContext sourceEditor) {
        if (location == null || location.range() == null || location.range().start() == null) {
            return;
        }
        int line = location.range().start().line();
        int col = location.range().start().col();
        Path target = location.isLocal() ? sourceFile : pathFromUri(location.uri());
        if (target == null || !Files.isRegularFile(target)) {
            setStatusBarText("JavaScript: definition file not found");
            return;
        }
        Path normalizedTarget = target.toAbsolutePath().normalize();
        Path normalizedSource = sourceFile == null
                ? null
                : sourceFile.toAbsolutePath().normalize();
        SwingUtilities.invokeLater(() -> {
            if (normalizedTarget.equals(normalizedSource) && sourceEditor != null) {
                sourceEditor.setCaretPosition(line, col);
                return;
            }
            requestOpenFile(normalizedTarget);
            SwingUtilities.invokeLater(() -> setCaretPosition(line, col));
        });
    }

    private static Path pathFromUri(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(value);
            if (uri.getScheme() == null || uri.getScheme().isBlank()) {
                return Path.of(value).toAbsolutePath().normalize();
            }
            if ("file".equalsIgnoreCase(uri.getScheme())) {
                return Path.of(uri).toAbsolutePath().normalize();
            }
        } catch (Exception ignored) {
            try {
                return Path.of(value).toAbsolutePath().normalize();
            } catch (Exception ignoredAgain) {
                return null;
            }
        }
        return null;
    }

    private static String sourceLine(Path file,
                                     Path currentFile,
                                     String currentText,
                                     int line,
                                     Map<Path, List<String>> cache) {
        if (line < 0) {
            return "";
        }
        List<String> lines;
        if (currentText != null && currentFile != null
                && file.equals(currentFile.toAbsolutePath().normalize())) {
            lines = currentText.lines().toList();
        } else {
            lines = cache.computeIfAbsent(file, key -> {
                try {
                    return Files.readAllLines(key);
                } catch (Exception ignored) {
                    return List.of();
                }
            });
        }
        return line < lines.size() ? lines.get(line).strip() : "";
    }

    @Override
    public List<DocumentHighlight> getDocumentHighlights(IdeDocumentHighlightContext context) {
        if (context == null || context.filePath() == null) return List.of();
        WebkitLspService service = lspService;
        if (service == null) return List.of();
        List<DocumentHighlight> out = new java.util.ArrayList<>();
        for (Range range : service.highlights(WebkitPathConventions.normalizePath(context.filePath()), context.text(), context.line(), context.col())) out.add(DocumentHighlight.text(range));
        return out;
    }

    @Override
    public HoverInfo getHover(IdeHoverContext context) {
        if (context == null || context.filePath() == null) return null;
        WebkitLspService service = lspService;
        return service == null ? null : service.hover(WebkitPathConventions.normalizePath(context.filePath()), context.text(), context.line(), context.col());
    }

    @Override
    public SignatureHelp provideSignatureHelp(IdeSignatureHelpContext context) {
        if (context == null || context.filePath() == null) return null;
        WebkitLspService service = lspService;
        return service == null ? null : service.signatureHelp(WebkitPathConventions.normalizePath(context.filePath()), context.text(), context.caretLine(), context.caretCol());
    }

    @Override
    public List<TextEdit> computeRenameEdits(IdeRenameContext context) {
        if (context == null || context.filePath() == null) return List.of();
        WebkitLspService service = lspService;
        return service == null ? List.of() : service.rename(WebkitPathConventions.normalizePath(context.filePath()), context.text(), context.line(), context.col(), context.newName());
    }

    @Override
    public List<CodeAction> getCodeActions(IdeCodeActionContext context) {
        if (context == null || context.filePath() == null || context.range() == null) return List.of();
        WebkitLspService service = lspService;
        return service == null ? List.of() : service.codeActions(WebkitPathConventions.normalizePath(context.filePath()), context.text(), context.range());
    }


    private static String completionPrefix(IdeCompletionContext context) {
        String line = context.currentLine();
        int col = context.caretCol();
        if (line == null || col <= 0 || col > line.length()) {
            return orEmpty(context.prefix());
        }
        int start = col;
        while (start > 0 && isPrefixChar(line.charAt(start - 1))) {
            start--;
        }
        if (start < col && start > 0 && isPrefixLead(line.charAt(start - 1))) {
            start--;
        }
        String prefix = line.substring(start, col);
        return hasLetterOrDigit(prefix) ? prefix : orEmpty(context.prefix());
    }

    private static boolean isPrefixChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '-';
    }

    private static boolean isPrefixLead(char c) {
        return c == '@' || c == ':' || c == '#';
    }

    private static boolean hasLetterOrDigit(String value) {
        return value.chars().anyMatch(Character::isLetterOrDigit);
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    



    private Character triggerCharacterOf(IdeCompletionContext context) {
        if (context.triggerKind() != IdeCompletionTriggerKind.TYPING) {
            return null;
        }
        String line = context.currentLine();
        int col = context.caretCol();
        if (line == null || col <= 0 || col > line.length()) {
            return null;
        }
        char typed = line.charAt(col - 1);
        return getCompletionTriggerCharacters().contains(typed) ? typed : null;
    }

    @Override
    public boolean shouldAutoTriggerCompletion(IdeCompletionContext context) {
        if (context == null || !WebkitPathConventions.isHighlightable(context.filePath())) {
            return false;
        }
        String line = context.currentLine();
        int col = context.caretCol();
        if (line == null || col <= 0 || col > line.length()) {
            return false;
        }
        char typed = line.charAt(col - 1);
        if (WebkitPathConventions.isHtmlLike(context.filePath())) {
            return htmlWantsCompletion(context, typed);
        }
        return isCompletionChar(typed);
    }

    private static boolean isCompletionChar(char typed) {
        return Character.isLetter(typed) || typed == '.' || typed == '<' || typed == '>' || typed == '/' || typed == ':'
                || typed == '"' || typed == '\'' || typed == '-' || typed == '_'
                || typed == '@' || typed == '#' || typed == '$' || typed == '&';
    }


    private static boolean htmlWantsCompletion(IdeCompletionContext context, char typed) {
        if (insideHtmlAttributeValue(context.text(), context.caretOffset())) {
            return false;
        }
        if (typed == '<' || typed == '>' || typed == '/' || typed == '&' || typed == '"' || typed == '\'' || typed == ':') {
            return true;
        }
        if (!isCompletionChar(typed)) {
            return false;
        }
        String text = context.text();
        int offset = context.caretOffset();
        if (text == null || offset <= 0) {
            return false;
        }
        return insideTag(text, offset) || insideEmbeddedBlock(text, offset) || isBlankLineTagPrefix(context);
    }

    private static boolean isBlankLineTagPrefix(IdeCompletionContext context) {
        String line = context.currentLine();
        int col = context.caretCol();
        if (line == null || col <= 0 || col > line.length()) {
            return false;
        }
        return line.substring(0, col).trim().matches("[A-Za-z][A-Za-z0-9-]*");
    }

    private static boolean insideHtmlAttributeValue(String text, int offset) {
        if (text == null || offset <= 0) {
            return false;
        }
        int end = Math.min(offset, text.length());
        int tagStart = text.lastIndexOf('<', end - 1);
        if (tagStart < 0 || text.lastIndexOf('>', end - 1) > tagStart) {
            return false;
        }
        char quote = 0;
        for (int i = tagStart + 1; i < end; i++) {
            char c = text.charAt(i);
            if (quote == 0 && (c == '\'' || c == '"')) {
                quote = c;
            } else if (quote == c) {
                quote = 0;
            }
        }
        return quote != 0;
    }

    private static String htmlAttributePathPrefix(String text, int offset) {
        if (!insideHtmlAttributeValue(text, offset) || text == null) {
            return null;
        }
        int end = Math.min(offset, text.length());
        int tagStart = text.lastIndexOf('<', end - 1);
        String tag = text.substring(tagStart + 1, end);
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?is)(?:src|href)\\s*=\\s*(['\\\"])([^'\\\"]*)$")
                .matcher(tag);
        return matcher.find() ? matcher.group(2) : null;
    }

    @Override
    @CodeFormatProprity(Integer.MAX_VALUE)
    public String formatCode(FormatCodeContext context) {
        if (context == null || context.file() == null || !WebkitPathConventions.isHighlightable(context.file())) {
            return context == null ? null : context.text();
        }
        String source = context.formatScope() == IdeFormatScope.SELECTION
                ? context.text()
                : (context.fullText() == null ? context.text() : context.fullText());
        int tabSize = Math.max(1, context.tabSize());
        if (WebkitPathConventions.isHtmlLike(context.file())) {
            return formatHtml(source, tabSize, context.useSpacesForTab());
        }
        if (WebkitPathConventions.isJsLike(context.file())) {
            return formatJavaScript(source, tabSize, context.useSpacesForTab());
        }
        return source;
    }

    private static String formatJavaScript(String source, int tabSize, boolean useSpaces) {
        if (source == null || source.isBlank()) {
            return source;
        }
        String indentUnit = useSpaces ? " ".repeat(tabSize) : "\t";
        String[] lines = source.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        StringBuilder formatted = new StringBuilder(source.length());
        int depth = 0;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                formatted.append('\n');
                continue;
            }
            int lineDepth = Math.max(0, depth - leadingClosingBraces(trimmed));
            formatted.append(indentUnit.repeat(lineDepth)).append(trimmed).append('\n');
            depth = Math.max(0, depth + braceBalance(trimmed));
        }
        return formatted.toString();
    }

    private static int braceBalance(String text) {
        int balance = 0;
        char quote = 0;
        boolean escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == quote) quote = 0;
                continue;
            }
            if (c == '\'' || c == '"' || c == '`') quote = c;
            else if (c == '{') balance++;
            else if (c == '}') balance--;
        }
        return balance;
    }

    private static int leadingClosingBraces(String text) {
        int count = 0;
        while (count < text.length() && text.charAt(count) == '}') count++;
        return count;
    }

    private static String formatHtml(String source, int tabSize, boolean useSpaces) {
        if (source == null || source.isBlank()) {
            return source;
        }
        String indentUnit = useSpaces ? " ".repeat(tabSize) : "\t";
        String[] lines = source.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        StringBuilder formatted = new StringBuilder(source.length());
        int depth = 0;
        boolean multilineTag = false;
        String multilineTagName = "";
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                formatted.append('\n');
                continue;
            }
            boolean closing = trimmed.startsWith("</");
            if (closing) depth = Math.max(0, depth - 1);
            int lineDepth = multilineTag && !trimmed.equals(">") && !trimmed.endsWith(">") ? depth + 1 : depth;
            formatted.append(indentUnit.repeat(Math.max(0, lineDepth))).append(trimmed).append('\n');

            if (trimmed.startsWith("<") && !closing && !trimmed.startsWith("<!--")) {
                String tag = htmlTagName(trimmed);
                boolean complete = trimmed.endsWith(">") && !trimmed.endsWith("/>");
                if (complete && !isVoidHtmlTag(tag) && !trimmed.contains("</")) depth++;
                multilineTag = !trimmed.endsWith(">");
                if (multilineTag) multilineTagName = tag;
            }
            if (multilineTag && trimmed.endsWith(">")) {
                if (!isVoidHtmlTag(multilineTagName) && !trimmed.endsWith("/>")) depth++;
                multilineTag = false;
                multilineTagName = "";
            }
        }
        return formatted.toString();
    }

    private static String htmlTagName(String value) {
        int start = value.startsWith("</") ? 2 : (value.startsWith("<") ? 1 : 0);
        int end = start;
        while (end < value.length() && (Character.isLetterOrDigit(value.charAt(end)) || value.charAt(end) == '-')) end++;
        return value.substring(start, end).toLowerCase(java.util.Locale.ROOT);
    }

    private static boolean isVoidHtmlTag(String tag) {
        return Set.of("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "source", "track", "wbr").contains(tag);
    }

    private static boolean insideTag(String text, int offset) {
        int end = Math.min(offset, text.length());
        return text.lastIndexOf('<', end - 1) > text.lastIndexOf('>', end - 1);
    }

    private static boolean insideEmbeddedBlock(String text, int offset) {
        return insideBlock(text, offset, "<script", "</script")
                || insideBlock(text, offset, "<style", "</style");
    }

    private static boolean insideBlock(String text, int offset, String open, String close) {
        int openAt = lastIndexOfIgnoreCase(text, open, offset);
        return openAt >= 0 && openAt > lastIndexOfIgnoreCase(text, close, offset);
    }

    private static int lastIndexOfIgnoreCase(String text, String token, int end) {
        int limit = Math.max(0, end - HTML_CONTEXT_SCAN_WINDOW);
        for (int i = Math.min(end, text.length()) - token.length(); i >= limit; i--) {
            if (text.regionMatches(true, i, token, 0, token.length())) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public boolean isAutoCompletionOnTypingEnabled() {
        return true;
    }


    @Override
    public Set<Character> getCompletionTriggerCharacters() {
        WebkitLspService service = lspService;
        if (service == null) {
            return DEFAULT_COMPLETION_TRIGGERS;
        }
        Set<Character> characters = new HashSet<>(DEFAULT_COMPLETION_TRIGGERS);
        characters.addAll(service.completionTriggerCharacters());
        return characters;
    }

    private void applyInitialSyntaxHighlight(IdeEditorContext context) {
        try {
            context.setSyntaxHighlightEnabled(true);
            context.applySyntaxHighlight();
        } catch (Exception e) {
            log.debug("Falha ao aplicar realce inicial: {}", e.getMessage());
        }
    }

    private void startLanguageServerFor(Path filePath, String text) {
        WebkitLspService service = ensureLspService();
        if (service == null) {
            return;
        }
        Path project = projectPath;
        lspSetupExecutor.execute(() -> {
            service.startForFile(filePath, project, progressListener());
            service.sync(filePath, text);
        });
    }

    private synchronized WebkitLspService ensureLspService() {
        if (lspService != null) {
            return lspService;
        }
        try {
            WebkitLspService service = new WebkitLspService(getResource(), resolveDownloadObserver());
            service.setDiagnosticsListener(file -> SwingUtilities.invokeLater(() -> requestRefreshDiagnostics(file)));
            lspService = service;
        } catch (Exception e) {
            log.warn("Não foi possível preparar o serviço de LSP web: {}", e.getMessage());
        }
        return lspService;
    }

    private DownloadObserver resolveDownloadObserver() {
        try {
            return getService(DownloadObserver.class);
        } catch (Exception e) {
            log.debug("DownloadObserver indisponível: {}", e.getMessage());
            return null;
        }
    }

    private WebkitLspService.DownloadProgressListener progressListener() {
        return new WebkitLspService.DownloadProgressListener() {
            @Override
            public void onStart(String id, String label) {
                SwingUtilities.invokeLater(() -> showProgress(id, label));
            }

            @Override
            public void onProgress(String id, String label, int percent) {
                SwingUtilities.invokeLater(() -> updateProgress(id, label, percent));
            }

            @Override
            public void onFinish(String id) {
                SwingUtilities.invokeLater(() -> hideProgress(id));
            }
        };
    }

}
