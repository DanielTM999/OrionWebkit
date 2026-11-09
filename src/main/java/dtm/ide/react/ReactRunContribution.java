package dtm.ide.react;

import dtm.ide.api.extension.runconfig.*;
import javax.swing.*;
import java.awt.GridLayout;
import java.util.Map;

public final class ReactRunContribution implements RunConfigurationContribution {
    @Override
    public String getType() { return ReactRunSupport.TYPE; }

    @Override
    public String getDisplayName() { return "React: iniciar servidor"; }

    @Override
    public Icon getIcon() { return ReactIcons.react(16); }

    @Override
    public RunConfigurationForm createForm() { return new Form(); }

    private static final class Form implements RunConfigurationForm {
        private final JPanel panel = new JPanel(new GridLayout(0, 1, 6, 6));
        private final JTextField directory = new JTextField(".");
        private final JTextField script = new JTextField();
        private final JCheckBox launchBrowser = new JCheckBox("Abrir navegador ao iniciar o servidor");
        private RunConfigurationData current;

        private Form() {
            launchBrowser.setOpaque(false);
            panel.add(new JLabel("Pasta da aplicação (relativa ao projeto ou absoluta)"));
            panel.add(directory);
            panel.add(new JLabel("Script npm (vazio: detectar dev/start)"));
            panel.add(script);
            panel.add(launchBrowser);
        }

        @Override
        public JComponent getComponent() { return panel; }

        @Override
        public RunConfigurationData getData() {
            var result = RunConfigurationData.builder().type(ReactRunSupport.TYPE)
                    .title("React: iniciar servidor")
                    .properties(Map.of("directory", directory.getText().trim(), "script", script.getText().trim(),
                            "launchBrowser", launchBrowser.isSelected())).build();
            if (current != null) {
                result.setId(current.getId());
                result.setTitle(current.getTitle());
            }
            return result;
        }

        @Override
        public void setData(RunConfigurationData data) {
            current = data;
            directory.setText(".");
            script.setText("");
            launchBrowser.setSelected(false);
            if (data != null && data.getProperties() != null) {
                directory.setText(String.valueOf(data.getProperties().getOrDefault("directory", ".")));
                script.setText(String.valueOf(data.getProperties().getOrDefault("script", "")));
                launchBrowser.setSelected(Boolean.parseBoolean(String.valueOf(data.getProperties().getOrDefault("launchBrowser", false))));
            }
        }
    }
}
