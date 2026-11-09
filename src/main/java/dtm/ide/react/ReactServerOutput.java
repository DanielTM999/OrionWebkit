package dtm.ide.react;

import lombok.extern.slf4j.Slf4j;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import java.util.regex.Pattern;

@Slf4j
final class ReactServerOutput extends FilterInputStream {
    private static final Pattern ANSI = Pattern.compile("\u001b\\[[0-?]*[ -/]*[@-~]");
    private static final Pattern LOCAL_URL = Pattern.compile("https?://(?:localhost|127\\.0\\.0\\.1|0\\.0\\.0\\.0|\\[::1?\\]):[0-9]+(?:/[^\\s<>\"']*)?");
    private final ByteArrayOutputStream line = new ByteArrayOutputStream();
    private final Consumer<String> browser;
    private boolean opened;

    ReactServerOutput(InputStream input, Consumer<String> browser) {
        super(input);
        this.browser = browser;
    }

    @Override
    public int read() throws IOException {
        int value = in.read();
        if (value >= 0) observe(value);
        else finishLine();
        return value;
    }

    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
        int count = in.read(bytes, offset, length);
        for (int index = 0; index < count; index++) observe(bytes[offset + index] & 0xff);
        if (count < 0) finishLine();
        return count;
    }

    private void observe(int value) {
        if (opened) return;
        if (value == '\n' || value == '\r') finishLine();
        else if (line.size() < 16384) line.write(value);
    }

    private void finishLine() {
        if (opened || line.size() == 0) return;
        String text = ANSI.matcher(line.toString(StandardCharsets.UTF_8)).replaceAll("");
        line.reset();
        var match = LOCAL_URL.matcher(text);
        if (!match.find()) return;
        opened = true;
        String url = match.group().replace("0.0.0.0", "127.0.0.1").replace("[::]", "[::1]");
        try {
            browser.accept(url);
        } catch (RuntimeException error) {
            log.warn("Falha ao abrir navegador para {}", url, error);
        }
    }
}
