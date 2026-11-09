package dtm.ide.react;

import dtm.ide.api.extension.runconfig.RunConfigurationData;
import org.junit.jupiter.api.Test;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ReactRunIntegrationTest {
    @Test
    void launchesConfiguredProjectAndServesItsPage() throws Exception {
        String project = System.getProperty("webkit.reactTestProject");
        assumeTrue(project != null);
        ReactRunSupport support = new ReactRunSupport();
        var data = RunConfigurationData.builder().type(ReactRunSupport.TYPE).id("integration")
                .properties(Map.of("directory", ".", "script", "")).build();
        var handle = support.launch(null, data, Path.of(project));
        CompletableFuture<String> address = new CompletableFuture<>();
        StringBuilder output = new StringBuilder();
        Thread reader = Thread.ofVirtual().start(() -> {
            try (var lines = new BufferedReader(new InputStreamReader(handle.getOutput()))) {
                String line;
                while ((line = lines.readLine()) != null) {
                    output.append(line).append('\n');
                    var match = Pattern.compile("http://localhost:[0-9]+/").matcher(line);
                    if (match.find()) address.complete(match.group());
                }
                address.completeExceptionally(new IllegalStateException(output.toString()));
            } catch (Exception error) { address.completeExceptionally(error); }
        });
        try {
            // The fixture binds to 0.0.0.0 (IPv4); localhost may resolve to ::1.
            URI url = URI.create(address.get(30, TimeUnit.SECONDS).replace("localhost", "127.0.0.1"));
            var response = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
                    .send(HttpRequest.newBuilder(url)
                    .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("/src/"), response.body());
            assertTrue(handle.isAlive());
        } finally {
            support.stop(data);
            reader.join(5000);
        }
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            while (handle.isAlive()) Thread.sleep(20);
        });
    }
}
