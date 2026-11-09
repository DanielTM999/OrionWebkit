package dtm.ide.react;

import dtm.di.annotations.Singleton;
import dtm.ide.api.annotations.PluginReference;
import dtm.ide.api.plugin.PluginScope;
import dtm.ide.api.extension.wizard.*;
import javax.swing.*;
import java.util.Collection;
import java.util.List;

@Singleton
@PluginReference(id = "webkit-react-project-wizard", singleton = true, scope = PluginScope.APPLICATION)
public class ReactProjectWizardProvider extends ProjectWizardProvider {
    @Override
    public Collection<ProjectWizard> getProjectWizards() { return List.of(new Wizard()); }

    private static final class Wizard implements ProjectWizard {
        @Override
        public String getId() { return "webkit:react"; }
        @Override
        public String getName() { return "React application"; }
        @Override
        public String getLanguage() { return "JavaScript / TypeScript • React"; }
        @Override
        public Icon getIcon() { return ReactIcons.react(24); }

        @Override
        public JPanel getView(ProjectWizardCallback callback) {
            return new ReactProjectWizardView(callback);
        }
    }
}
