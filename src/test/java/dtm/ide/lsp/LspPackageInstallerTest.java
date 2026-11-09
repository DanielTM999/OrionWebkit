package dtm.ide.lsp;

import dtm.ide.api.extension.Resource;
import dtm.ide.services.sdk.LspPackageInstaller;
import dtm.ide.utils.NodeSdkUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class LspPackageInstallerTest {
    @TempDir Path root;

    private LspPackageInstaller installer() {
        Resource resource = (Resource) Proxy.newProxyInstance(Resource.class.getClassLoader(),
                new Class<?>[]{Resource.class}, (proxy, method, args) -> root);
        return new LspPackageInstaller(resource);
    }

    @Test
    void resolvesWebAndTypescriptInSeparatePrefixesAndIgnoresLegacyInstallation() throws Exception {
        Path legacy = root.resolve("sdk/lsp/node_modules/typescript-language-server/lib/cli.mjs");
        Files.createDirectories(legacy.getParent());
        Files.writeString(legacy, "legacy");
        LspPackageInstaller installer = installer();
        assertTrue(installer.resolveServerScript(LspServerKind.TYPESCRIPT).isEmpty());
        Path ts = root.resolve("sdk/lsp/typescript/node_modules/typescript-language-server/lib/cli.mjs");
        Path html = root.resolve("sdk/lsp/web/node_modules/vscode-langservers-extracted/bin/vscode-html-language-server");
        Files.createDirectories(ts.getParent());
        Files.createDirectories(html.getParent());
        Files.writeString(ts, "typescript");
        Files.writeString(html, "html");
        assertEquals(ts, installer.resolveServerScript(LspServerKind.TYPESCRIPT).orElseThrow());
        assertEquals(html, installer.resolveServerScript(LspServerKind.HTML).orElseThrow());
        assertTrue(installer.resolveTypescriptLibDir().isEmpty());
        Path lib = Files.createDirectories(root.resolve("sdk/lsp/typescript/node_modules/typescript/lib"));
        assertEquals(lib, installer.resolveTypescriptLibDir().orElseThrow());
    }

    @Test
    void installsWebWhileTypescriptIsRunningWithoutRemovingIt() throws Exception {
        assumeTrue(Boolean.getBoolean("webkit.installationIntegrationTest"));
        Path node = NodeSdkUtils.findNodeOnPath().orElseThrow();
        LspPackageInstaller installer = installer();
        Path ts = installer.ensureServerScript(LspServerKind.TYPESCRIPT, node);
        WebkitLspProcess server = new WebkitLspProcess(LspServerKind.TYPESCRIPT, ignored -> { }, ignored -> { });
        try {
            server.start(List.of(node.toString(), ts.toString(), "--stdio"), ts.getParent(), root,
                    WebkitLspSettings.initializationOptions(LspServerKind.TYPESCRIPT,
                            installer.resolveTypescriptLibDir().orElseThrow().toString()));
            assertTrue(server.isRunning(), server.getLastError());
            Path html = installer.ensureServerScript(LspServerKind.HTML, node);
            assertTrue(Files.isRegularFile(html));
            assertTrue(installer.resolveServerScript(LspServerKind.CSS).isPresent());
            assertEquals(ts, installer.ensureServerScript(LspServerKind.TYPESCRIPT, node));
            assertTrue(server.isRunning(), server.getLastError());
            assertTrue(installer.resolveTypescriptLibDir().isPresent());
        } finally {
            server.stop();
        }
    }
}
