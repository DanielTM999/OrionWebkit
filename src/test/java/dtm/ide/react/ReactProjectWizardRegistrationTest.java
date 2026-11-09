package dtm.ide.react;

import dtm.ide.api.annotations.PluginReference;
import dtm.ide.api.extension.wizard.ProjectWizardProvider;
import dtm.ide.api.plugin.PluginScope;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import static org.junit.jupiter.api.Assertions.*;

class ReactProjectWizardRegistrationTest {
    @Test
    void declaresWizardProviderInPluginManifest() throws Exception {
        var reference = ReactProjectWizardProvider.class.getAnnotation(PluginReference.class);
        assertNotNull(reference);
        assertEquals(PluginScope.APPLICATION, reference.scope());
        assertTrue(ProjectWizardProvider.class.isAssignableFrom(ReactProjectWizardProvider.class));
        try (var stream = getClass().getResourceAsStream("/META-INF/plugin-configs.json")) {
            assertNotNull(stream);
            var manifest = new JsonMapper().readTree(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            var ids = new HashSet<String>();
            for (var plugin : manifest.path("plugins")) assertTrue(ids.add(plugin.path("id").asString()));
            assertTrue(ids.contains(reference.id()), "Project wizard must be registered in the plugin manifest");
            assertTrue(ids.contains("orion-webkit-adapter"));
            boolean packageRegistered = false;
            for (var entry : manifest.path("pluginPackages")) {
                if (ReactProjectWizardProvider.class.getPackageName().startsWith(entry.asString() + ".")) packageRegistered = true;
            }
            assertTrue(packageRegistered);
        }
    }

    @Test
    void contributesReactToTheNewProjectWizardList() {
        var wizards = new ReactProjectWizardProvider().getProjectWizards();
        assertEquals(1, wizards.size());
        var wizard = wizards.iterator().next();
        assertEquals("webkit:react", wizard.getId());
        assertEquals("React application", wizard.getName());
        assertTrue(wizard.getLanguage().contains("TypeScript"));
    }
}
