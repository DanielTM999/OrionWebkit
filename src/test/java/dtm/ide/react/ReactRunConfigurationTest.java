package dtm.ide.react;

import dtm.ide.WebkitAdapter;
import dtm.ide.api.extension.runconfig.RunConfigurationData;
import org.junit.jupiter.api.Test;
import javax.swing.SwingUtilities;
import static org.junit.jupiter.api.Assertions.*;

class ReactRunConfigurationTest {
    @Test
    void enablesRunForReactAndLeavesOtherContributionsAlone() throws Exception {
        class Adapter extends WebkitAdapter {
            Boolean run;
            Boolean debug;
            @Override public void requestSetRunButtonEnabled(boolean value) { run = value; }
            @Override public void requestSetDebugButtonEnabled(boolean value) { debug = value; }
        }
        Adapter adapter = new Adapter();
        adapter.onRunConfigurationChanged(null);
        adapter.onRunConfigurationChanged(RunConfigurationData.builder().type("other").build());
        SwingUtilities.invokeAndWait(() -> { });
        assertNull(adapter.run);
        assertNull(adapter.debug);
        adapter.onRunConfigurationChanged(RunConfigurationData.builder().type(ReactRunSupport.TYPE).build());
        SwingUtilities.invokeAndWait(() -> { });
        assertEquals(Boolean.TRUE, adapter.run);
        assertEquals(Boolean.FALSE, adapter.debug);
    }
}
