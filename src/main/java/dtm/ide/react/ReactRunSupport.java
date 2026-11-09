package dtm.ide.react;

import dtm.ide.api.extension.Resource;
import dtm.ide.api.extension.runconfig.*;
import dtm.ide.utils.NodeSdkUtils;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class ReactRunSupport {
    public static final String TYPE = "webkit.react.server";
    private final Map<Process, String> processes = new ConcurrentHashMap<>();

    public static ProcessBuilder npm(Resource resource, Path directory, String... arguments) {
        Path node = (resource == null ? Optional.<Path>empty() : NodeSdkUtils.findNodeExecutable(resource.getResourcePath().resolve("sdk")))
                .or(NodeSdkUtils::findNodeOnPath)
                .orElseThrow(() -> new IllegalStateException("Instale Node.js com npm para executar aplicações React."));
        Path npm = node.getParent().resolve("node_modules/npm/bin/npm-cli.js");
        if (!Files.isRegularFile(npm)) npm = node.getParent().resolve("../lib/node_modules/npm/bin/npm-cli.js").normalize();
        if (!Files.isRegularFile(npm)) throw new IllegalStateException("npm não encontrado junto ao Node.js: " + node);
        List<String> command = new ArrayList<>(List.of(node.toString(), npm.toString()));
        command.addAll(List.of(arguments));
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true);
        builder.environment().put("PATH", node.getParent() + System.getProperty("path.separator") + System.getenv().getOrDefault("PATH", ""));
        return builder;
    }

    public RunProcessHandle launch(Resource resource, RunConfigurationData data, Path project) throws Exception {
        if (!TYPE.equals(data.getType())) throw new IllegalArgumentException("Tipo de execução desconhecido.");
        var properties = data.getProperties() == null ? Map.<String, Object>of() : data.getProperties();
        String directory = String.valueOf(properties.getOrDefault("directory", ".")).trim();
        Path root = project.resolve(directory).toAbsolutePath().normalize();
        String script = String.valueOf(properties.getOrDefault("script", "")).trim();
        if (script.isEmpty()) script = ReactProjectDetector.serverScript(root);
        if (!script.matches("[a-zA-Z0-9:_-]+")) throw new IllegalArgumentException("Nome de script inválido.");
        Process process = npm(resource, root, "run", script).start();
        processes.put(process, key(data));
        process.onExit().thenRun(() -> processes.remove(process));
        return RunProcessHandle.builder().output(process.getInputStream()).readonly(true)
                .alive(process::isAlive).terminate(() -> terminate(process)).build();
    }

    private void terminate(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        processes.remove(process);
    }

    public void stopAll() {
        processes.keySet().forEach(this::terminate);
    }

    private String key(RunConfigurationData data) {
        return data.getId() == null ? TYPE : data.getId();
    }

    public void stop(RunConfigurationData data) {
        String configuration = key(data);
        processes.forEach((process, id) -> {
            if (configuration.equals(id)) terminate(process);
        });
    }
}
