package dtm.ide.react;

import dtm.ide.api.extension.runconfig.RunConfigurationData;
import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ReactBrowserOptionTest {
    @Test
    void savesAndRestoresBrowserPreferenceAndResetsForNewConfigurations() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var form = new ReactRunContribution().createForm();
            JCheckBox checkbox = Arrays.stream(((JPanel) form.getComponent()).getComponents())
                    .filter(JCheckBox.class::isInstance).map(JCheckBox.class::cast).findFirst().orElseThrow();
            assertFalse(checkbox.isSelected());
            checkbox.setSelected(true);
            var saved = form.getData();
            assertEquals(true, saved.getProperties().get("launchBrowser"));
            saved.setId("react-server");
            saved.setTitle("My app");
            form.setData(saved);
            assertTrue(checkbox.isSelected());
            assertEquals("react-server", form.getData().getId());
            assertEquals("My app", form.getData().getTitle());
            checkbox.setSelected(false);
            assertEquals(false, form.getData().getProperties().get("launchBrowser"));
            form.setData(RunConfigurationData.builder().properties(Map.of("launchBrowser", "true")).build());
            assertTrue(checkbox.isSelected());
            form.setData(RunConfigurationData.builder().properties(Map.of()).build());
            assertFalse(checkbox.isSelected());
            form.setData(null);
            assertFalse(checkbox.isSelected());
        });
    }

    @Test
    void detectsActualPortAcrossChunksPreservesOutputAndOpensOnlyOnce() throws Exception {
        String text = "Vite ready\nDocumentation: https://vite.dev/\n"
                + "Local: \u001b[36mhttp://\u001b[1mlocalhost:\u001b[22m5174/\u001b[0m\r\n"
                + "Network: http://192.168.1.1:5174/\nLocal: http://localhost:5174/\n";
        List<String> opened = new ArrayList<>();
        try (var input = new ReactServerOutput(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), opened::add)) {
            var output = new ByteArrayOutputStream();
            byte[] chunk = new byte[3];
            int count;
            while ((count = input.read(chunk)) >= 0) output.write(chunk, 0, count);
            assertEquals(text, output.toString(StandardCharsets.UTF_8));
        }
        assertEquals(List.of("http://localhost:5174/"), opened);
    }

    @Test
    void normalizesWildcardAddressesAndHandlesSingleByteReadsWithoutFinalNewline() throws Exception {
        for (var entry : Map.of("http://0.0.0.0:3000/", "http://127.0.0.1:3000/",
                "http://[::]:3001/", "http://[::1]:3001/").entrySet()) {
            List<String> opened = new ArrayList<>();
            try (var input = new ReactServerOutput(new ByteArrayInputStream(entry.getKey().getBytes(StandardCharsets.UTF_8)), opened::add)) {
                while (input.read() >= 0) { }
            }
            assertEquals(List.of(entry.getValue()), opened);
        }
    }

    @Test
    void browserFailureDoesNotInterruptConsoleOutput() throws Exception {
        byte[] text = "http://127.0.0.1:3000/\nserver running\n".getBytes(StandardCharsets.UTF_8);
        try (var input = new ReactServerOutput(new ByteArrayInputStream(text), ignored -> {
            throw new IllegalStateException("browser unavailable");
        })) {
            assertArrayEquals(text, input.readAllBytes());
        }
    }
}
