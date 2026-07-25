package dtm.ide;


import dtm.di.annotations.Singleton;
import dtm.ide.api.annotations.CooperativeAdapter;
import dtm.ide.api.annotations.PluginReference;
import dtm.ide.api.context.IdeProjectContext;
import dtm.ide.api.extension.IdeAdapter;
import dtm.ide.api.project.editor.DocumentHighlight;
import dtm.ide.api.project.editor.IdeCompletionContext;
import dtm.ide.api.project.editor.IdeCompletionTriggerKind;
import dtm.ide.api.project.editor.IdeDiagnosticsContext;
import dtm.ide.api.project.editor.IdeDocumentHighlightContext;
import dtm.ide.api.project.editor.IdeDefinitionContext;
import dtm.ide.api.project.editor.IdeHoverContext;
import dtm.ide.api.project.editor.IdeSignatureHelpContext;
import dtm.ide.api.project.editor.IdeRenameContext;
import dtm.ide.api.project.editor.IdeCodeActionContext;
import dtm.ide.api.project.editor.IdeEditorContext;
import dtm.ide.api.theme.EditorTheme;
import dtm.ide.editor.theme.WebkitEditorTheme;
import dtm.ide.lsp.WebkitLspService;
import dtm.ide.lsp.JsTsSnippets;
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
import lombok.extern.slf4j.Slf4j;
import javax.swing.SwingUtilities;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Singleton
@CooperativeAdapter
@PluginReference(id = "orion-webkit-adapter")
public class WebkitAdapter extends IdeAdapter {

    private static final Set<Character> DEFAULT_COMPLETION_TRIGGERS =
            Set.of('.', '<', '/', ':', '"', '\'', '-', '@', '$');
    
    private static final int HTML_CONTEXT_SCAN_WINDOW = 50_000;

    private final WebkitEditorRegistry editorRegistry = new WebkitEditorRegistry();
    private final EditorTheme editorTheme = new WebkitEditorTheme();
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
        List<AutoCompleteItem> snippets = javascriptContext ? JsTsSnippets.matching(prefix) : List.of();
        WebkitLspService service = lspService;
        if (service == null) {
            return snippets;
        }
        Path file = WebkitPathConventions.normalizePath(context.filePath());
        if (!service.isRunningFor(file)) {
            startLanguageServerFor(file, context.text());
            return snippets;
        }
        List<AutoCompleteItem> lspItems = service.complete(file, context.text(), context.caretLine(), context.caretCol(), prefix, triggerCharacterOf(context));
        if (!javascriptContext) {
            return lspItems;
        }
        List<AutoCompleteItem> out = new java.util.ArrayList<>(lspItems.size() + snippets.size());
        if (JsTsSnippets.isExactTrigger(prefix)) {
            out.addAll(snippets);
            out.addAll(lspItems);
        } else {
            out.addAll(lspItems);
            out.addAll(snippets);
        }
        return out;
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

    private List<Location> locations(IdeDefinitionContext context, boolean references) {
        if (context == null || context.filePath() == null || !WebkitPathConventions.isHighlightable(context.filePath())) return List.of();
        WebkitLspService service = lspService;
        return service == null ? List.of() : service.definitions(WebkitPathConventions.normalizePath(context.filePath()), context.text(), context.line(), context.col(), references);
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
        return Character.isLetter(typed) || typed == '.' || typed == '<' || typed == '/' || typed == ':'
                || typed == '"' || typed == '\'' || typed == '-' || typed == '_'
                || typed == '@' || typed == '#' || typed == '$' || typed == '&';
    }


    private static boolean htmlWantsCompletion(IdeCompletionContext context, char typed) {
        if (typed == '<' || typed == '/' || typed == '&' || typed == '"' || typed == '\'' || typed == ':') {
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
        return insideTag(text, offset) || insideEmbeddedBlock(text, offset);
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
