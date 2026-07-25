package dtm.ide.lsp;

import dtm.stools.component.panels.editor.code.api.Range;
import dtm.stools.component.panels.editor.code.api.TextEdit;
import dtm.stools.component.panels.editor.code.api.Location;
import dtm.stools.component.panels.editor.code.api.CodeAction;
import dtm.stools.component.panels.editor.code.hover.HoverInfo;
import dtm.stools.component.panels.editor.code.signature.ParameterInformation;
import dtm.stools.component.panels.editor.code.signature.SignatureHelp;
import dtm.stools.component.panels.editor.code.signature.SignatureInformation;
import dtm.stools.component.panels.editor.code.autocomplete.AutoCompleteItem;
import dtm.stools.component.panels.editor.code.diagnostics.Diagnostic;
import dtm.stools.component.panels.editor.code.diagnostics.DiagnosticSeverity;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;







@Slf4j
public final class WebkitLspProcess {

    private static final long INITIALIZE_TIMEOUT_MS = 20_000;
    private static final long SHUTDOWN_TIMEOUT_MS = 2_000;
    private static final long COMPLETION_TIMEOUT_MS = 3_000;
    
    private static final long RESOLVE_BUDGET_MS = 1_500;
    private static final int MAX_COMPLETION_ITEMS = 200;
    
    private static final int RESOLVE_LIMIT = 25;

    public enum State {
        NOT_STARTED, STARTING, RUNNING, STOPPED, ERROR
    }

    private final LspServerKind kind;
    private final Consumer<String> onDiagnosticsPublished;
    private final Object lock = new Object();

    private volatile Process process;
    private volatile WebkitLspJsonRpcClient client;
    private volatile State state = State.NOT_STARTED;
    private volatile String lastError;
    private volatile boolean completionResolveProvider;
    private volatile Set<Character> completionTriggerCharacters = Set.of();
    
    private final Set<String> serverCapabilities = ConcurrentHashMap.newKeySet();

    private final Map<String, Integer> documentVersions = new ConcurrentHashMap<>();
    private final Map<String, String> lastSyncedText = new ConcurrentHashMap<>();
    private final Map<String, List<JsonNode>> diagnosticsByUri = new ConcurrentHashMap<>();

    public WebkitLspProcess(LspServerKind kind, Consumer<String> onDiagnosticsPublished) {
        this.kind = kind;
        this.onDiagnosticsPublished = onDiagnosticsPublished;
    }

    public LspServerKind kind() {
        return kind;
    }

    public State getState() {
        return state;
    }

    public String getLastError() {
        return lastError;
    }

    public boolean isRunning() {
        Process p = process;
        return p != null && p.isAlive() && state == State.RUNNING;
    }

    public void start(List<String> command, Path workingDirectory, Path rootPath, Map<String, Object> initializationOptions) {
        synchronized (lock) {
            if (isRunning()) {
                return;
            }
            state = State.STARTING;
            try {
                ProcessBuilder builder = new ProcessBuilder(command);
                if (workingDirectory != null) {
                    builder.directory(workingDirectory.toFile());
                }
                Process started = builder.start();
                process = started;
                drainStderrQuietly(started);

                WebkitLspJsonRpcClient rpc = new WebkitLspJsonRpcClient(started.getInputStream(), started.getOutputStream());
                rpc.onNotification("textDocument/publishDiagnostics", this::onPublishDiagnostics);
                
                
                rpc.onRequest("workspace/configuration", WebkitLspProcess::onConfigurationRequest);
                client = rpc;

                handshake(rpc, rootPath, initializationOptions);
                state = State.RUNNING;
                log.info("Servidor de linguagem {} iniciado (pid {}).", kind, started.pid());
            } catch (Exception e) {
                lastError = e.getMessage();
                state = State.ERROR;
                log.warn("Falha ao iniciar o servidor de linguagem {}: {}", kind, e.getMessage());
                stopInternal();
            }
        }
    }

    private void handshake(WebkitLspJsonRpcClient rpc, Path rootPath, Map<String, Object> initializationOptions) throws Exception {
        String rootUri = rootPath == null ? null : rootPath.toUri().toString();
        Map<String, Object> params = new HashMap<>();
        params.put("processId", (int) ProcessHandle.current().pid());
        params.put("rootUri", rootUri);
        params.put("rootPath", rootPath == null ? null : rootPath.toAbsolutePath().toString());
        params.put("capabilities", clientCapabilities());
        if (initializationOptions != null && !initializationOptions.isEmpty()) {
            params.put("initializationOptions", initializationOptions);
        }
        JsonNode result = rpc.sendRequest("initialize", params).get(INITIALIZE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        readServerCapabilities(result);
        rpc.sendNotification("initialized", Map.of());
        
        
        rpc.sendNotification("workspace/didChangeConfiguration", Map.of("settings", Map.of(
                "html", WebkitLspSettings.forSection("html"),
                "css", WebkitLspSettings.forSection("css"),
                "scss", WebkitLspSettings.forSection("scss"),
                "less", WebkitLspSettings.forSection("less"),
                "javascript", WebkitLspSettings.forSection("javascript"),
                "typescript", WebkitLspSettings.forSection("typescript")
        )));
    }

    




    private static Map<String, Object> clientCapabilities() {
        Map<String, Object> completionItem = Map.of(
                "snippetSupport", true,
                "commitCharactersSupport", false,
                "documentationFormat", List.of("markdown", "plaintext"),
                "deprecatedSupport", true,
                "preselectSupport", true,
                "insertReplaceSupport", false,
                "labelDetailsSupport", true,
                "resolveSupport", Map.of("properties", List.of("documentation", "detail", "additionalTextEdits"))
        );
        return Map.of(
                "textDocument", Map.of(
                        "synchronization", Map.of("didSave", false, "willSave", false),
                        "publishDiagnostics", Map.of("relatedInformation", false),
                        "completion", Map.of(
                                "dynamicRegistration", false,
                                "contextSupport", true,
                                "completionItem", completionItem
                        ),
                        "hover", Map.of("contentFormat", List.of("markdown", "plaintext")),
                        "signatureHelp", Map.of("dynamicRegistration", false),
                        "definition", Map.of("dynamicRegistration", false),
                        "references", Map.of("dynamicRegistration", false),
                        "documentHighlight", Map.of("dynamicRegistration", false),
                        "rename", Map.of("prepareSupport", true),
                        "codeAction", Map.of("dynamicRegistration", false,
                                "codeActionLiteralSupport", Map.of("codeActionKind", Map.of("valueSet", List.of("", "quickfix", "refactor", "source"))))
                ),
                "workspace", Map.of(
                        "workspaceFolders", false,
                        "configuration", true,
                        "didChangeConfiguration", Map.of("dynamicRegistration", false)
                )
        );
    }

    private void readServerCapabilities(JsonNode initializeResult) {
        if (initializeResult == null || initializeResult.isNull()) {
            return;
        }
        JsonNode capabilities = initializeResult.path("capabilities");
        for (String name : List.of("hoverProvider", "definitionProvider", "referencesProvider", "documentHighlightProvider", "renameProvider", "codeActionProvider", "signatureHelpProvider")) {
            if (capabilities.path(name).asBoolean(false) || capabilities.path(name).isObject()) serverCapabilities.add(name);
        }
        JsonNode completionProvider = capabilities.path("completionProvider");
        completionResolveProvider = completionProvider.path("resolveProvider").asBoolean(false);
        JsonNode triggers = completionProvider.get("triggerCharacters");
        if (triggers != null && triggers.isArray()) {
            Set<Character> characters = new LinkedHashSet<>();
            for (JsonNode trigger : triggers) {
                String value = trigger.asString("");
                if (!value.isEmpty()) {
                    characters.add(value.charAt(0));
                }
            }
            completionTriggerCharacters = Set.copyOf(characters);
        }
    }

    private JsonNode request(String method, Path file, String text, int line, int character, Map<String, Object> extra) {
        if (client == null || !isRunning() || file == null) return null;
        syncDocument(file, text);
        Map<String, Object> params = new HashMap<>(extra);
        params.put("textDocument", Map.of("uri", toUri(file)));
        params.put("position", Map.of("line", line, "character", character));
        try { return client.sendRequest(method, params).get(3, TimeUnit.SECONDS); }
        catch (Exception e) { log.debug("Falha LSP {}: {}", method, e.getMessage()); return null; }
    }

    public List<Location> definitions(Path file, String text, int line, int character, boolean references) {
        String capability = references ? "referencesProvider" : "definitionProvider";
        if (!serverCapabilities.contains(capability)) return List.of();
        JsonNode result = request(references ? "textDocument/references" : "textDocument/definition", file, text, line, character,
                references ? Map.of("context", Map.of("includeDeclaration", true)) : Map.of());
        return parseLocations(result);
    }

    public List<Range> highlights(Path file, String text, int line, int character) {
        if (!serverCapabilities.contains("documentHighlightProvider")) return List.of();
        JsonNode result = request("textDocument/documentHighlight", file, text, line, character, Map.of());
        if (result == null || !result.isArray()) return List.of();
        List<Range> out = new ArrayList<>(); for (JsonNode n : result) { Range r = parseRange(n.get("range")); if (r != null) out.add(r); } return out;
    }

    public HoverInfo hover(Path file, String text, int line, int character) {
        if (!serverCapabilities.contains("hoverProvider")) return null;
        JsonNode result = request("textDocument/hover", file, text, line, character, Map.of());
        if (result == null || result.isNull()) return null;
        String content = markup(result.get("contents")); if (content.isBlank()) return null;
        Range r = parseRange(result.get("range"));
        return r == null ? HoverInfo.markdown(content) : new HoverInfo(content, HoverInfo.ContentType.MARKDOWN, r.start().line(), r.start().col(), r.end().line(), r.end().col());
    }

    public SignatureHelp signatureHelp(Path file, String text, int line, int character) {
        if (!serverCapabilities.contains("signatureHelpProvider")) return null;
        JsonNode result = request("textDocument/signatureHelp", file, text, line, character, Map.of());
        if (result == null || !result.path("signatures").isArray()) return null;
        List<SignatureInformation> signatures = new ArrayList<>();
        for (JsonNode s : result.get("signatures")) { List<ParameterInformation> ps = new ArrayList<>(); for (JsonNode p : s.path("parameters")) ps.add(new ParameterInformation(p.path("label").isTextual() ? p.path("label").asString("") : p.path("label").toString(), markup(p.get("documentation")))); signatures.add(new SignatureInformation(s.path("label").asString(""), markup(s.get("documentation")), ps)); }
        return signatures.isEmpty() ? null : new SignatureHelp(signatures, result.path("activeSignature").asInt(0), result.path("activeParameter").asInt(0));
    }

    public List<TextEdit> rename(Path file, String text, int line, int character, String newName) {
        if (!serverCapabilities.contains("renameProvider") || newName == null || newName.isBlank()) return List.of();
        JsonNode result = request("textDocument/rename", file, text, line, character, Map.of("newName", newName));
        return editsForCurrentDocument(result, toUri(file));
    }

    public List<CodeAction> codeActions(Path file, String text, Range range) {
        if (!serverCapabilities.contains("codeActionProvider") || range == null) return List.of();
        Map<String, Object> params = new HashMap<>();
        params.put("textDocument", Map.of("uri", toUri(file)));
        params.put("range", lspRange(range));
        params.put("context", Map.of("diagnostics", List.of()));
        try {
            JsonNode result = client.sendRequest("textDocument/codeAction", params).get(3, TimeUnit.SECONDS);
            if (result == null || !result.isArray()) return List.of();
            List<CodeAction> out = new ArrayList<>();
            for (JsonNode action : result) {
                String title = action.path("title").asString(""); if (title.isBlank()) continue;
                List<TextEdit> edits = editsForCurrentDocument(action.get("edit"), toUri(file));
                String kind = action.path("kind").asString("");
                out.add(kind.startsWith("refactor") ? CodeAction.refactor(title, edits) : CodeAction.quickFix(title, edits));
            }
            return out;
        } catch (Exception e) { log.debug("Falha LSP textDocument/codeAction: {}", e.getMessage()); return List.of(); }
    }

    private static Map<String, Object> lspRange(Range range) { return Map.of("start", Map.of("line", range.start().line(), "character", range.start().col()), "end", Map.of("line", range.end().line(), "character", range.end().col())); }
    private static List<TextEdit> editsForCurrentDocument(JsonNode workspaceEdit, String uri) {
        if (workspaceEdit == null || workspaceEdit.isNull()) return List.of();
        JsonNode edits = workspaceEdit.path("changes").get(uri);
        if (edits == null || !edits.isArray()) return List.of();
        return parseAdditionalTextEdits(edits);
    }

    private static List<Location> parseLocations(JsonNode node) {
        if (node == null || node.isNull()) return List.of(); List<Location> out = new ArrayList<>();
        if (node.isObject()) addLocation(out, node);
        else if (node.isArray()) for (JsonNode n : node) addLocation(out, n);
        return out;
    }
    private static void addLocation(List<Location> out, JsonNode n) { String uri = n.path("uri").asString(n.path("targetUri").asString("")); Range r = parseRange(n.has("range") ? n.get("range") : n.get("targetSelectionRange")); if (r != null) out.add(uri.isBlank() ? Location.local(r) : Location.of(uri, r)); }
    private static Range parseRange(JsonNode n) { if (n == null || n.isNull()) return null; return Range.of(n.path("start").path("line").asInt(), n.path("start").path("character").asInt(), n.path("end").path("line").asInt(), n.path("end").path("character").asInt()); }
    private static String markup(JsonNode n) { if (n == null || n.isNull()) return ""; if (n.isTextual()) return n.asString(""); if (n.isArray()) { StringBuilder b = new StringBuilder(); for (JsonNode e : n) { if (!b.isEmpty()) b.append("\n\n"); b.append(markup(e)); } return b.toString(); } return n.path("value").asString(""); }

    



    private static Object onConfigurationRequest(JsonNode params) {
        JsonNode items = params == null ? null : params.get("items");
        if (items == null || !items.isArray()) {
            return List.of();
        }
        List<Object> settings = new ArrayList<>(items.size());
        for (JsonNode item : items) {
            settings.add(WebkitLspSettings.forSection(item.path("section").asString("")));
        }
        return settings;
    }

    public Set<Character> completionTriggerCharacters() {
        return completionTriggerCharacters;
    }

    









    public void syncDocument(Path filePath, String text) {
        WebkitLspJsonRpcClient rpc = client;
        if (rpc == null || !isRunning() || filePath == null) {
            return;
        }
        String uri = toUri(filePath);
        String safeText = text == null ? "" : text;
        if (safeText.equals(lastSyncedText.put(uri, safeText))) {
            return;
        }
        Integer version = documentVersions.get(uri);
        if (version == null) {
            documentVersions.put(uri, 1);
            rpc.sendNotification("textDocument/didOpen", Map.of(
                    "textDocument", Map.of(
                            "uri", uri,
                            "languageId", languageIdFor(filePath),
                            "version", 1,
                            "text", safeText
                    )
            ));
        } else {
            int next = version + 1;
            documentVersions.put(uri, next);
            rpc.sendNotification("textDocument/didChange", Map.of(
                    "textDocument", Map.of("uri", uri, "version", next),
                    "contentChanges", List.of(Map.of("text", safeText))
            ));
        }
    }

    








    public List<AutoCompleteItem> complete(Path filePath, String text, int line, int character,
                                           String prefix, Character triggerChar) {
        WebkitLspJsonRpcClient rpc = client;
        if (rpc == null || !isRunning() || filePath == null) {
            return Collections.emptyList();
        }
        syncDocument(filePath, text);
        String uri = toUri(filePath);
        Map<String, Object> context = triggerChar == null
                ? Map.of("triggerKind", 1)
                : Map.of("triggerKind", 2, "triggerCharacter", String.valueOf(triggerChar));
        try {
            JsonNode result = rpc.sendRequest("textDocument/completion", Map.of(
                    "textDocument", Map.of("uri", uri),
                    "position", Map.of("line", line, "character", character),
                    "context", context
            )).get(COMPLETION_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            return parseCompletions(rpc, result, prefix);
        } catch (Exception e) {
            log.debug("Falha ao obter completions do servidor {}: {}", kind, e.getMessage());
            return Collections.emptyList();
        }
    }


    private List<AutoCompleteItem> parseCompletions(WebkitLspJsonRpcClient rpc, JsonNode result, String prefix) {
        if (result == null || result.isNull()) {
            return Collections.emptyList();
        }
        JsonNode items = result.isArray() ? result : result.get("items");
        if (items == null || !items.isArray() || items.isEmpty()) {
            return Collections.emptyList();
        }
        List<JsonNode> raw = new ArrayList<>(items.size());
        for (JsonNode item : items) {
            if (!labelOf(item).isBlank()) {
                raw.add(item);
            }
        }
        raw = rank(raw, prefix == null ? "" : prefix);
        if (raw.size() > MAX_COMPLETION_ITEMS) {
            raw = raw.subList(0, MAX_COMPLETION_ITEMS);
        }

        List<JsonNode> resolved = resolveDocumentation(rpc, raw);
        List<AutoCompleteItem> out = new ArrayList<>(resolved.size());
        for (JsonNode item : resolved) {
            AutoCompleteItem parsed = parseCompletionItem(item);
            if (parsed != null) {
                out.add(parsed);
            }
        }
        return out;
    }

    








    private List<JsonNode> resolveDocumentation(WebkitLspJsonRpcClient rpc, List<JsonNode> items) {
        if (!completionResolveProvider || items.isEmpty()) {
            return items;
        }
        List<CompletableFuture<JsonNode>> pending = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            JsonNode item = items.get(i);
            if (i >= RESOLVE_LIMIT || !needsResolve(item)) {
                pending.add(CompletableFuture.completedFuture(item));
                continue;
            }
            pending.add(rpc.sendRequest("completionItem/resolve", item)
                    .handle((node, error) -> node == null || node.isNull() ? item : node));
        }
        long deadline = System.currentTimeMillis() + RESOLVE_BUDGET_MS;
        List<JsonNode> out = new ArrayList<>(items.size());
        for (int i = 0; i < pending.size(); i++) {
            try {
                out.add(pending.get(i).get(Math.max(0, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS));
            } catch (Exception e) {
                out.add(items.get(i));
            }
        }
        return out;
    }

    



    private static boolean needsResolve(JsonNode item) {
        JsonNode data = item.get("data");
        if (data == null || data.isNull()) {
            return false;
        }
        boolean hasDocumentation = !documentationOf(item.get("documentation")).isBlank();
        boolean hasEdits = item.path("additionalTextEdits").isArray() && !item.path("additionalTextEdits").isEmpty();
        return !hasDocumentation || !hasEdits;
    }

  
    private static List<JsonNode> rank(List<JsonNode> items, String prefix) {
        List<Ranked> ranked = new ArrayList<>(items.size());
        for (JsonNode item : items) {
            ranked.add(new Ranked(item, matchScore(item, prefix), sortKeyOf(item), labelOf(item)));
        }
        if (!prefix.isEmpty()) {
            List<Ranked> matches = ranked.stream().filter(entry -> entry.score() < NO_MATCH).toList();
            if (!matches.isEmpty()) {
                ranked = new ArrayList<>(matches);
            }
        }
        ranked.sort(comparator(prefix, hasMeaningfulSortText(ranked)));
        return ranked.stream().map(Ranked::item).toList();
    }

    private static Comparator<Ranked> comparator(String prefix, boolean serverOrders) {
        if (prefix.isEmpty()) {
            return Comparator.comparing(Ranked::sortKey).thenComparing(Ranked::label);
        }
        Comparator<Ranked> byRelevance = Comparator.comparingInt(Ranked::score);
        if (serverOrders) {
            byRelevance = byRelevance.thenComparing(Ranked::sortKey);
        }
        
        return byRelevance.thenComparingInt(entry -> entry.label().length()).thenComparing(Ranked::label);
    }

    





    private static boolean hasMeaningfulSortText(List<Ranked> items) {
        return items.stream().anyMatch(entry -> !entry.sortKey().equals(entry.label()));
    }

    
    private record Ranked(JsonNode item, int score, String sortKey, String label) {
    }

    private static final int NO_MATCH = 4;

    
    private static int matchScore(JsonNode item, String prefix) {
        String candidate = item.path("filterText").asString("");
        if (candidate.isBlank()) {
            candidate = labelOf(item);
        }
        if (candidate.equals(prefix)) {
            return 0;
        }
        if (candidate.startsWith(prefix)) {
            return 1;
        }
        String lowerCandidate = candidate.toLowerCase(Locale.ROOT);
        String lowerPrefix = prefix.toLowerCase(Locale.ROOT);
        if (lowerCandidate.startsWith(lowerPrefix)) {
            return 2;
        }
        return lowerCandidate.contains(lowerPrefix) ? 3 : NO_MATCH;
    }

    private static String labelOf(JsonNode item) {
        return item.path("label").asString("").trim();
    }

    private static String sortKeyOf(JsonNode item) {
        String sortText = item.path("sortText").asString("");
        return sortText.isBlank() ? labelOf(item) : sortText;
    }

    




    private static AutoCompleteItem parseCompletionItem(JsonNode item) {
        String label = labelOf(item);
        if (label.isBlank()) {
            return null;
        }

        String insertText = firstNonBlank(
                item.path("textEdit").path("newText").asString(""),
                item.path("insertText").asString(""),
                label);
        boolean isSnippet = item.path("insertTextFormat").asInt(1) == 2;
        String detail = firstNonBlank(
                item.path("detail").asString(""),
                item.path("labelDetails").path("description").asString(""),
                item.path("labelDetails").path("detail").asString(""));
        if (isDeprecated(item)) {
            detail = detail.isBlank() ? "(obsoleto)" : detail + "  (obsoleto)";
        }
        String documentation = documentationOf(item.get("documentation"));
        List<TextEdit> additionalEdits = parseAdditionalTextEdits(item.get("additionalTextEdits"));
        AutoCompleteItem.Kind kind = completionKind(item.path("kind").asInt(1), isSnippet);
        return new AutoCompleteItem(insertText, label, detail, documentation, null, kind, additionalEdits);
    }

    private static AutoCompleteItem.Kind completionKind(int lspKind, boolean isSnippet) {
        if (isSnippet) {
            return AutoCompleteItem.Kind.SNIPPET;
        }
        String name = switch (lspKind) {
            case 2 -> "METHOD";
            case 3 -> "FUNCTION";
            case 4 -> "CONSTRUCTOR";
            case 5 -> "FIELD";
            case 6 -> "VARIABLE";
            case 7 -> "CLASS";
            case 8 -> "INTERFACE";
            case 9 -> "MODULE";
            case 10 -> "PROPERTY";
            case 11 -> "UNIT";
            case 12 -> "VALUE";
            case 13 -> "ENUM";
            case 14 -> "KEYWORD";
            case 15 -> "SNIPPET";
            case 16 -> "COLOR";
            case 17 -> "FILE";
            case 18 -> "REFERENCE";
            case 19 -> "FOLDER";
            case 20 -> "ENUM_MEMBER";
            case 21 -> "CONSTANT";
            case 22 -> "STRUCT";
            case 23 -> "EVENT";
            case 24 -> "OPERATOR";
            case 25 -> "TYPE_PARAMETER";
            default -> "TEXT";
        };
        try {
            return AutoCompleteItem.Kind.valueOf(name);
        } catch (IllegalArgumentException ignored) {
            return AutoCompleteItem.Kind.TEXT;
        }
    }

    private static boolean isDeprecated(JsonNode item) {
        if (item.path("deprecated").asBoolean(false)) {
            return true;
        }
        JsonNode tags = item.get("tags");
        if (tags == null || !tags.isArray()) {
            return false;
        }
        for (JsonNode tag : tags) {
            if (tag.asInt(0) == 1) {
                return true;
            }
        }
        return false;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String documentationOf(JsonNode node) {
        if (node == null || node.isNull()) {
            return "";
        }
        if (node.isTextual()) {
            return node.asString("");
        }
        return node.path("value").asString("");
    }

    private static List<TextEdit> parseAdditionalTextEdits(JsonNode node) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            return List.of();
        }
        List<TextEdit> out = new ArrayList<>(node.size());
        for (JsonNode edit : node) {
            JsonNode range = edit.get("range");
            if (range == null || range.isNull()) {
                continue;
            }
            JsonNode start = range.get("start");
            JsonNode end = range.get("end");
            if (start == null || end == null) {
                continue;
            }
            Range r = Range.of(
                    start.path("line").asInt(0), start.path("character").asInt(0),
                    end.path("line").asInt(0), end.path("character").asInt(0));
            out.add(TextEdit.replace(r, edit.path("newText").asString("")));
        }
        return out;
    }

    



    public void closeDocument(Path filePath) {
        if (filePath == null) {
            return;
        }
        String uri = toUri(filePath);
        documentVersions.remove(uri);
        lastSyncedText.remove(uri);
        diagnosticsByUri.remove(uri);
        WebkitLspJsonRpcClient rpc = client;
        if (rpc != null && isRunning()) {
            rpc.sendNotification("textDocument/didClose", Map.of("textDocument", Map.of("uri", uri)));
        }
    }

    public List<Diagnostic> diagnostics(Path filePath) {
        if (filePath == null) {
            return Collections.emptyList();
        }
        List<JsonNode> raw = diagnosticsByUri.get(toUri(filePath));
        if (raw == null || raw.isEmpty()) {
            return Collections.emptyList();
        }
        List<Diagnostic> out = new ArrayList<>(raw.size());
        for (JsonNode node : raw) {
            Diagnostic diagnostic = parseDiagnostic(node);
            if (diagnostic != null) {
                out.add(diagnostic);
            }
        }
        return out;
    }

    private void onPublishDiagnostics(JsonNode params) {
        if (params == null || params.isNull()) {
            return;
        }
        String uri = params.path("uri").asString("");
        if (uri.isBlank()) {
            return;
        }
        JsonNode items = params.get("diagnostics");
        List<JsonNode> list = new ArrayList<>();
        if (items != null && items.isArray()) {
            items.forEach(list::add);
        }
        diagnosticsByUri.put(uri, list);
        Consumer<String> listener = onDiagnosticsPublished;
        if (listener != null) {
            listener.accept(uri);
        }
    }

    private static Diagnostic parseDiagnostic(JsonNode node) {
        JsonNode range = node.get("range");
        if (range == null || range.isNull()) {
            return null;
        }
        JsonNode start = range.get("start");
        JsonNode end = range.get("end");
        if (start == null || end == null) {
            return null;
        }
        int startLine = start.path("line").asInt(0);
        int startCol = start.path("character").asInt(0);
        int endLine = end.path("line").asInt(startLine);
        int endCol = end.path("character").asInt(startCol);
        String message = node.path("message").asString("");
        DiagnosticSeverity severity = switch (node.path("severity").asInt(1)) {
            case 2 -> DiagnosticSeverity.WARNING;
            case 3 -> DiagnosticSeverity.INFO;
            case 4 -> DiagnosticSeverity.HINT;
            default -> DiagnosticSeverity.ERROR;
        };
        return new Diagnostic(startLine, startCol, endLine, endCol, severity, message);
    }

    private String languageIdFor(Path filePath) {
        String extension = dtm.ide.utils.WebkitPathConventions.extensionOf(filePath);
        return switch (extension) {
            case "htm" -> "html";
            case "scss" -> "scss";
            case "sass" -> "sass";
            case "less" -> "less";
            case "jsx" -> "javascriptreact";
            case "tsx" -> "typescriptreact";
            case "ts" -> "typescript";
            case "mjs", "cjs", "js" -> "javascript";
            case "css" -> "css";
            default -> switch (kind) {
                case HTML -> "html";
                case CSS -> "css";
                case TYPESCRIPT -> "javascript";
            };
        };
    }

    private static String toUri(Path filePath) {
        return filePath.toAbsolutePath().normalize().toUri().toString();
    }

    public void stop() {
        synchronized (lock) {
            stopInternal();
        }
    }

    private void stopInternal() {
        WebkitLspJsonRpcClient rpc = client;
        if (rpc != null) {
            try {
                rpc.sendRequest("shutdown", null).get(SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                rpc.sendNotification("exit", null);
            } catch (Exception ignored) {
            }
            rpc.close();
            client = null;
        }
        Process p = process;
        if (p != null && p.isAlive()) {
            p.destroy();
            try {
                if (!p.waitFor(5, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                p.destroyForcibly();
            }
        }
        diagnosticsByUri.clear();
        documentVersions.clear();
        lastSyncedText.clear();
        state = State.STOPPED;
    }

    private void drainStderrQuietly(Process started) {
        Thread drain = new Thread(() -> {
            try (var err = started.getErrorStream()) {
                byte[] buffer = new byte[8192];
                while (err.read(buffer) != -1) {
                    
                }
            } catch (IOException ignored) {
            }
        }, "orion-webkit-lsp-" + kind.name().toLowerCase(Locale.ROOT) + "-stderr");
        drain.setDaemon(true);
        drain.start();
    }
}
