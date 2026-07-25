package dtm.ide.services.sdk;

import dtm.ide.api.exceptions.DisplayException;
import dtm.ide.api.extension.Resource;
import dtm.ide.utils.NodeSdkUtils;
import dtm.request_actions.http.download.core.DownloadObserver;
import dtm.request_actions.http.download.core.client.DownloadObserverStreamClient;
import dtm.request_actions.http.download.core.config.ObserverConfiguration;
import dtm.stools.component.popup.ModernDialog;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;





@Slf4j
public class NodeSdkInstaller {

    public static final String NODE_PROGRESS_ID = "downloadNodeSdk";
    public static final String DEFAULT_NODE_VERSION = "22.23.1";

    private static final int DOWNLOAD_MAX_ATTEMPTS = 3;
    private static final long DOWNLOAD_RETRY_BASE_DELAY_MS = 1500;
    private static final String SDK_DIR = "sdk";

    private final Object installLock = new Object();
    private final Resource resource;
    private final DownloadObserver downloadObserver;

    public NodeSdkInstaller(Resource resource, DownloadObserver downloadObserver) {
        this.resource = resource;
        this.downloadObserver = downloadObserver;
    }

    public boolean isReady() {
        return getNodePath().isPresent();
    }

    public Optional<Path> getNodePath() {
        Path sdkFolder = sdkRoot();
        if (sdkFolder != null) {
            Optional<Path> bundled = NodeSdkUtils.findNodeExecutable(sdkFolder);
            if (bundled.isPresent()) {
                return bundled;
            }
        }
        return NodeSdkUtils.findNodeOnPath();
    }

    public Optional<Path> getNodeRoot() {
        return getNodePath().map(NodeSdkInstaller::rootFromExecutable);
    }

    public Path ensureNode(DownloadProgressListener progressListener) {
        synchronized (installLock) {
            Optional<Path> existing = getNodePath();
            if (existing.isPresent()) {
                return existing.get();
            }
            Path sdkFolder = sdkRoot();
            if (sdkFolder == null) {
                throw displayException("Diretório de recursos do plugin indisponível.", null);
            }
            DownloadProgressListener listener = progressListener == null ? DownloadProgressListener.NOOP : progressListener;
            downloadAndInstallNode(sdkFolder, listener);
            return NodeSdkUtils.findNodeExecutable(sdkFolder).orElseThrow(() ->
                    displayException("O Node.js foi baixado, mas o executável não foi encontrado.", null));
        }
    }

    




    static Path rootFromExecutable(Path nodeExecutable) {
        if (nodeExecutable == null) {
            return null;
        }
        Path parent = nodeExecutable.getParent();
        if (parent != null && "bin".equalsIgnoreCase(String.valueOf(parent.getFileName()))) {
            Path grandParent = parent.getParent();
            return grandParent != null ? grandParent : parent;
        }
        return parent;
    }

    private Path sdkRoot() {
        Path base = resourcePath();
        return base == null ? null : base.resolve(SDK_DIR).toAbsolutePath().normalize();
    }

    private Path resourcePath() {
        try {
            return resource == null ? null : resource.getResourcePath();
        } catch (Exception e) {
            return null;
        }
    }

    private void downloadAndInstallNode(Path sdkFolder, DownloadProgressListener listener) {
        if (downloadObserver == null) {
            throw displayException("Serviço de download indisponível.", null);
        }
        NodeArtifact artifact = nodeArtifact(DEFAULT_NODE_VERSION);
        Path staging = sdkFolder.resolveSibling(sdkFolder.getFileName() + "-node-staging");
        try {
            Files.createDirectories(staging);
        } catch (IOException e) {
            throw displayException("Não foi possível preparar a pasta temporária do Node.js.", e);
        }
        Path archive = staging.resolve(artifact.fileName());

        listener.onStart(NODE_PROGRESS_ID, artifact.displayName());
        try {
            Exception lastError = null;
            for (int attempt = 1; attempt <= DOWNLOAD_MAX_ATTEMPTS; attempt++) {
                try {
                    downloadToFile(artifact, archive, listener);
                    listener.onProgress(NODE_PROGRESS_ID, "Extraindo Node.js", -1);
                    extractAndFlatten(archive, staging, sdkFolder);
                    return;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw displayException("Download do Node.js interrompido.", e);
                } catch (DisplayException e) {
                    throw e;
                } catch (Exception e) {
                    lastError = e;
                    deleteQuietly(archive);
                    if (attempt < DOWNLOAD_MAX_ATTEMPTS) {
                        log.warn("Falha ao baixar Node.js (tentativa {}/{}): {}. Tentando novamente...",
                                attempt, DOWNLOAD_MAX_ATTEMPTS, safeMessage(e));
                        sleepBackoff(attempt);
                    }
                }
            }
            throw displayException("Falha ao baixar o Node.js após " + DOWNLOAD_MAX_ATTEMPTS + " tentativas.", lastError);
        } finally {
            listener.onFinish(NODE_PROGRESS_ID);
            deleteRecursivelyQuietly(staging);
        }
    }

    private void downloadToFile(NodeArtifact artifact, Path target, DownloadProgressListener listener) throws Exception {
        Path temp = target.resolveSibling(target.getFileName() + ".part");
        Files.deleteIfExists(temp);

        CompletableFuture<Path> done = new CompletableFuture<>();
        AtomicBoolean finished = new AtomicBoolean(false);
        int[] lastPercent = {-1};
        OutputStream output = Files.newOutputStream(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);

        try {
            downloadObserver.newDownloadGetStream(
                    artifact.url(),
                    Map.of("User-Agent", "OrionWebkit/1.0"),
                    new DownloadObserverStreamClient() {
                        @Override
                        public void observerConfiguration(ObserverConfiguration observerConfiguration) {
                            observerConfiguration.setBufferSize(1024 * 128);
                            observerConfiguration.setTimeout(15, TimeUnit.MINUTES);
                            observerConfiguration.setReadTimeout(2, TimeUnit.MINUTES);
                            observerConfiguration.setMaxSizeDownload(512L * 1024L * 1024L);
                        }

                        @Override
                        public void onProgress(byte[] content, long bytesRead, long expectedSize, Map<String, List<String>> headers) {
                            try {
                                output.write(content);
                                if (expectedSize > 0) {
                                    int percent = (int) Math.clamp((bytesRead * 100L) / expectedSize, 0, 99);
                                    if (percent != lastPercent[0]) {
                                        lastPercent[0] = percent;
                                        listener.onProgress(NODE_PROGRESS_ID, artifact.displayName(), percent);
                                    }
                                }
                            } catch (Exception e) {
                                done.completeExceptionally(e);
                                throw new CompletionException(e);
                            }
                        }

                        @Override
                        public void onComplete(Map<String, List<String>> headers) {
                            done.complete(temp);
                        }

                        @Override
                        public void onError(Throwable exception) {
                            done.completeExceptionally(exception);
                        }

                        @Override
                        public void onDisconect() {
                            done.completeExceptionally(new IllegalStateException("Download desconectado: " + artifact.url()));
                        }
                    });

            done.get();
            output.close();
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            finished.set(true);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            throw new IllegalStateException(cause);
        } finally {
            closeQuietly(output);
            if (!finished.get()) {
                Files.deleteIfExists(temp);
            }
        }
    }

    private static void extractAndFlatten(Path archive, Path stagingDir, Path targetSdkDir) throws IOException {
        Path extractedRoot = stagingDir.resolve("extracted");
        Files.createDirectories(extractedRoot);
        installArchive(archive, extractedRoot);

        List<Path> topLevel;
        try (Stream<Path> entries = Files.list(extractedRoot)) {
            topLevel = entries.toList();
        }
        Path sourceDir = topLevel.size() == 1 && Files.isDirectory(topLevel.getFirst())
                ? topLevel.getFirst()
                : extractedRoot;

        Files.createDirectories(targetSdkDir);
        moveDirectoryContents(sourceDir, targetSdkDir);
        makeExecutablesRunnable(targetSdkDir);
    }

    private static void moveDirectoryContents(Path from, Path to) throws IOException {
        try (Stream<Path> children = Files.list(from)) {
            for (Path child : children.toList()) {
                Files.move(child, to.resolve(child.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static void installArchive(Path archive, Path targetDir) throws IOException {
        String name = archive.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".tar.gz") || name.endsWith(".tgz")) {
            extractTarGz(archive, targetDir);
        } else if (name.endsWith(".zip")) {
            extractZipInto(archive, targetDir);
        } else {
            throw new IllegalStateException("Formato de arquivo não suportado: " + archive.getFileName());
        }
    }

    private static void extractTarGz(Path archive, Path targetDir) throws IOException {
        Path normalizedTarget = targetDir.toAbsolutePath().normalize();
        try (GZIPInputStream gzip = new GZIPInputStream(Files.newInputStream(archive));
             TarArchiveInputStream tar = new TarArchiveInputStream(gzip)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                Path output = normalizedTarget.resolve(entry.getName()).normalize();
                if (!output.startsWith(normalizedTarget)) {
                    throw new IllegalStateException("Entrada tar inválida: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(output);
                } else if (entry.isSymbolicLink()) {
                    
                    
                } else if (entry.isFile()) {
                    Path parent = output.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    Files.copy(tar, output, StandardCopyOption.REPLACE_EXISTING);
                    applyTarMode(output, entry.getMode());
                }
            }
        }
    }

    private static void extractZipInto(Path zip, Path targetDir) throws IOException {
        Path normalizedTarget = targetDir.toAbsolutePath().normalize();
        try (java.util.zip.ZipFile zipFile = new java.util.zip.ZipFile(zip.toFile())) {
            java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                java.util.zip.ZipEntry entry = entries.nextElement();
                Path output = normalizedTarget.resolve(entry.getName()).normalize();
                if (!output.startsWith(normalizedTarget)) {
                    continue;
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(output);
                } else {
                    Path parent = output.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    try (InputStream in = zipFile.getInputStream(entry)) {
                        Files.copy(in, output, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }
    }

    private static void applyTarMode(Path file, int mode) {
        if (isWindows() || mode <= 0) {
            return;
        }
        if ((mode & 0100) != 0) {
            file.toFile().setExecutable(true, false);
        }
    }

    private static void makeExecutablesRunnable(Path sdkFolder) {
        if (isWindows()) {
            return;
        }
        Path bin = sdkFolder.resolve("bin");
        if (!Files.isDirectory(bin)) {
            return;
        }
        try (Stream<Path> entries = Files.list(bin)) {
            entries.filter(Files::isRegularFile).forEach(path -> path.toFile().setExecutable(true, false));
        } catch (IOException ignored) {
        }
    }

    private static NodeArtifact nodeArtifact(String version) {
        String platform = nodePlatformArch();
        boolean windows = platform.startsWith("win");
        String ext = windows ? "zip" : "tar.gz";
        String fileName = "node-v" + version + "-" + platform + "." + ext;
        String url = "https://nodejs.org/dist/v" + version + "/" + fileName;
        return new NodeArtifact(fileName, url, "Baixando Node.js " + version);
    }

    private static String nodePlatformArch() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean arm64 = arch.contains("aarch64") || arch.contains("arm64");
        if (os.contains("win")) {
            return arm64 ? "win-arm64" : "win-x64";
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return arm64 ? "darwin-arm64" : "darwin-x64";
        }
        if (os.contains("linux")) {
            return arm64 ? "linux-arm64" : "linux-x64";
        }
        throw displayException("Plataforma não suportada para download automático do Node.js: " + os + " / " + arch, null);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (Exception ignored) {
        }
    }

    private static void deleteRecursivelyQuietly(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(NodeSdkInstaller::deleteQuietly);
        } catch (IOException ignored) {
        }
    }

    private static void sleepBackoff(int attempt) {
        try {
            Thread.sleep(DOWNLOAD_RETRY_BASE_DELAY_MS * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String safeMessage(Throwable t) {
        if (t == null) {
            return "";
        }
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }

    private static void closeQuietly(OutputStream output) {
        try {
            output.close();
        } catch (Exception ignored) {
        }
    }

    private static DisplayException displayException(String message, Throwable cause) {
        DisplayException exception = cause == null
                ? new DisplayException(message)
                : new DisplayException(message, cause);
        return exception
                .title("Erro ao configurar o Node.js")
                .type(ModernDialog.Type.ERROR)
                .draggable(true);
    }

    private record NodeArtifact(String fileName, String url, String displayName) {
    }

    public interface DownloadProgressListener {
        DownloadProgressListener NOOP = new DownloadProgressListener() {
        };

        default void onStart(String id, String label) {
        }

        default void onProgress(String id, String label, int percent) {
        }

        default void onFinish(String id) {
        }
    }
}
