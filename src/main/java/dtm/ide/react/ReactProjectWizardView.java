package dtm.ide.react;

import dtm.ide.api.extension.wizard.ProjectWizardCallback;
import dtm.stools.component.feedback.alert.AlertPanel;
import dtm.stools.component.feedback.badge.BadgeLabel;
import dtm.stools.component.feedback.progress.ProgressBar;
import dtm.stools.component.feedback.steps.StepsPanel;
import dtm.stools.component.form.FormField;
import dtm.stools.component.inputfields.segmentedfield.SegmentedField;
import dtm.stools.component.inputfields.textfield.MaskedTextField;
import dtm.stools.component.panels.card.CardPanel;
import dtm.stools.component.panels.scroll.ScrollPanel;
import dtm.stools.configs.UiTokens;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

final class ReactProjectWizardView extends JPanel {
    private final ProjectWizardCallback callback;
    private final MaskedTextField name = sized(new MaskedTextField());
    private final MaskedTextField location = sized(new MaskedTextField());
    private final SegmentedField<Boolean> language = new SegmentedField<>();
    private final FormField nameField;
    private final FormField locationField;
    private final JLabel destination = muted(" ");
    private final StepsPanel steps = new StepsPanel();
    private final CardLayout pages = new CardLayout();
    private final JPanel body = transparent(pages);
    private final JPanel review = transparent(new GridBagLayout());
    private final JButton back = ReactWizardButtons.secondary("Voltar", null);
    private final JButton next = ReactWizardButtons.primary("Avançar", null);
    private final JButton cancel = ReactWizardButtons.ghost("Cancelar", null);
    private final JButton browse = ReactWizardButtons.secondary("Escolher pasta…", null);
    private final AlertPanel alert = new AlertPanel(AlertPanel.Severity.ERROR, "Revise os dados", "").setClosable(false);
    private final ProgressBar progress = new ProgressBar().setIndeterminate(true).setBarHeight(UiTokens.scale(4)).setShowLabel(false);
    private int step;
    private boolean busy;

    ReactProjectWizardView(ProjectWizardCallback callback) {
        super(new BorderLayout(0, UiTokens.space(3)));
        this.callback = callback;
        setOpaque(false);
        putClientProperty("orion.wizard.fitViewportHeight", true);
        setBorder(BorderFactory.createEmptyBorder(UiTokens.space(5), UiTokens.space(5), UiTokens.space(4), UiTokens.space(5)));
        name.setText("minha-aplicacao");
        name.setPlaceholder("minha-aplicacao");
        location.setText(System.getProperty("user.home", ""));
        location.setPlaceholder("Pasta onde o projeto será criado");
        language.addSegment("TypeScript", true).addSegment("JavaScript", false)
                .setArc(UiTokens.radius(UiTokens.Radius.SM)).setPreferredHeight(UiTokens.scale(30)).setSelectedIndex(0, false);
        nameField = new FormField("name", "Nome do projeto", name).setRequired(true)
                .setHelperText("Letras minúsculas, números e hífens. Ex.: minha-aplicacao");
        JPanel locationRow = transparent(new BorderLayout(UiTokens.space(2), 0));
        locationRow.add(location, BorderLayout.CENTER);
        locationRow.add(browse, BorderLayout.EAST);
        locationField = new FormField("location", "Localização", locationRow)
                .setHelperText("Uma nova pasta será criada dentro deste local.");
        body.add(scroll(projectPage()), "project");
        body.add(scroll(section("Revisão", "Confira os dados antes de criar o projeto.", review)), "review");
        add(header(), BorderLayout.NORTH);
        add(body, BorderLayout.CENTER);
        add(footer(), BorderLayout.SOUTH);
        browse.addActionListener(event -> chooseLocation());
        cancel.addActionListener(event -> { if (!busy && callback != null) callback.cancel(); });
        back.addActionListener(event -> showStep(0));
        next.addActionListener(event -> advance());
        DocumentListener changes = new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { updateDestination(); }
            @Override public void removeUpdate(DocumentEvent event) { updateDestination(); }
            @Override public void changedUpdate(DocumentEvent event) { updateDestination(); }
        };
        name.getDocument().addDocumentListener(changes);
        location.getDocument().addDocumentListener(changes);
        updateDestination();
        showStep(0);
    }

    private JComponent header() {
        JPanel heading = transparent(new BorderLayout(0, UiTokens.space(4)));
        JPanel row = transparent(new BorderLayout(UiTokens.space(4), 0));
        JLabel icon = new JLabel(ReactIcons.react(UiTokens.scale(42)));
        icon.setPreferredSize(new Dimension(UiTokens.scale(48), UiTokens.scale(48)));
        JLabel title = new JLabel("Aplicação React");
        title.setForeground(UiTokens.foreground());
        title.setFont(UiTokens.fontBold().deriveFont((float) UiTokens.scale(19)));
        JPanel copy = transparent(new BorderLayout(0, UiTokens.space(1)));
        copy.add(title, BorderLayout.NORTH);
        copy.add(muted("Crie sua aplicação com React e Vite."), BorderLayout.CENTER);
        BadgeLabel badge = new BadgeLabel("Vite", BadgeLabel.Tone.INFO);
        badge.setStyle(BadgeLabel.Style.SOFT).setSize(BadgeLabel.Size.MD);
        JPanel badgeHolder = transparent(new BorderLayout());
        badgeHolder.add(badge, BorderLayout.NORTH);
        row.add(icon, BorderLayout.WEST);
        row.add(copy, BorderLayout.CENTER);
        row.add(badgeHolder, BorderLayout.EAST);
        steps.setSteps(List.of("Projeto", "Revisão")).setClickable(false).setCircleSize(UiTokens.scale(24));
        heading.add(row, BorderLayout.NORTH);
        heading.add(steps, BorderLayout.SOUTH);
        return heading;
    }

    private JComponent projectPage() {
        JPanel form = transparent(new GridBagLayout());
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = 0;
        constraints.weightx = 1;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.anchor = GridBagConstraints.NORTHWEST;
        constraints.insets = new Insets(0, 0, UiTokens.space(3), 0);
        constraints.gridy = 0;
        form.add(locationField, constraints);
        constraints.gridy++;
        form.add(nameField, constraints);
        constraints.gridy++;
        form.add(new FormField("language", "Linguagem", language)
                .setHelperText("TypeScript adiciona verificação de tipos ao JavaScript."), constraints);
        constraints.gridy++;
        JPanel target = transparent(new BorderLayout(0, UiTokens.space(1)));
        target.add(muted("Pasta do projeto"), BorderLayout.NORTH);
        target.add(destination, BorderLayout.CENTER);
        form.add(target, constraints);
        return section("Identidade do projeto", "Escolha onde ele será criado e como será chamado.", form);
    }

    private JComponent footer() {
        alert.setVisible(false);
        progress.setVisible(false);
        JPanel actions = transparent(new FlowLayout(FlowLayout.RIGHT, UiTokens.space(2), 0));
        actions.add(cancel);
        actions.add(back);
        actions.add(next);
        JPanel footer = transparent(new BorderLayout(0, UiTokens.space(2)));
        footer.add(progress, BorderLayout.NORTH);
        footer.add(alert, BorderLayout.CENTER);
        footer.add(actions, BorderLayout.SOUTH);
        return footer;
    }

    private void showStep(int target) {
        if (busy) return;
        step = target;
        alert.setVisible(false);
        pages.show(body, target == 0 ? "project" : "review");
        steps.setCurrentStep(target, false);
        back.setVisible(target > 0);
        next.setText(target == 0 ? "Avançar" : "Criar projeto");
        next.setIcon(target == 0 ? null : ReactIcons.react(UiTokens.scale(16)));
        if (target == 1) refreshReview();
        revalidate();
        repaint();
    }

    private void refreshReview() {
        review.removeAll();
        summary(0, "Nome", name.getText().trim());
        summary(1, "Destino", targetPath().toString());
        summary(2, "Linguagem", Boolean.TRUE.equals(language.getSelectedValue()) ? "TypeScript" : "JavaScript");
        summary(3, "Estrutura", "React + Vite");
        summary(4, "Após criar", "Execute npm install e inicie o servidor React pela IDE.");
    }

    private void summary(int row, String key, String value) {
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = 0;
        constraints.gridy = row;
        constraints.anchor = GridBagConstraints.NORTHWEST;
        constraints.insets = new Insets(UiTokens.space(2), 0, UiTokens.space(2), UiTokens.space(4));
        review.add(muted(key), constraints);
        constraints.gridx = 1;
        constraints.weightx = 1;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.insets = new Insets(UiTokens.space(2), 0, UiTokens.space(2), 0);
        JTextArea label = new JTextArea(value);
        label.setEditable(false);
        label.setOpaque(false);
        label.setLineWrap(true);
        label.setWrapStyleWord(true);
        label.setFont(UiTokens.font());
        label.setForeground(UiTokens.foreground());
        review.add(label, constraints);
    }

    private void advance() {
        if (busy) return;
        nameField.clearError();
        locationField.clearError();
        if (!name.getText().trim().matches("[a-z][a-z0-9-]*")) {
            showStep(0);
            nameField.setError("Use um nome como minha-aplicacao.");
            name.requestFocusInWindow();
            return;
        }
        Path parent;
        try {
            if (location.getText().isBlank()) throw new IllegalArgumentException();
            parent = Path.of(location.getText().trim()).toAbsolutePath().normalize();
        } catch (Exception error) {
            showStep(0);
            locationField.setError("Informe uma pasta de destino válida.");
            location.requestFocusInWindow();
            return;
        }
        if (step == 0) showStep(1);
        else create(parent, name.getText().trim(), Boolean.TRUE.equals(language.getSelectedValue()));
    }

    private void create(Path parent, String projectName, boolean typescript) {
        setBusy(true);
        alert.setVisible(false);
        new SwingWorker<Path, Void>() {
            @Override
            protected Path doInBackground() throws Exception {
                if (!Files.isDirectory(parent)) throw new IllegalArgumentException("A localização precisa ser uma pasta existente.");
                return ReactScaffold.project(parent, projectName, typescript);
            }

            @Override
            protected void done() {
                setBusy(false);
                try {
                    Path created = get();
                    if (callback != null) callback.notifyProjectCreated(created);
                } catch (Exception error) {
                    Throwable cause = error.getCause() == null ? error : error.getCause();
                    String message = cause instanceof java.nio.file.FileAlreadyExistsException
                            ? "Já existe uma pasta com esse nome. Volte e escolha outro nome ou localização."
                            : cause.getMessage();
                    alert.setMessage(message == null ? "Não foi possível criar o projeto." : message).setVisible(true);
                    revalidate();
                }
            }
        }.execute();
    }

    private void setBusy(boolean value) {
        busy = value;
        progress.setVisible(value);
        next.setText(value ? "Criando projeto…" : "Criar projeto");
        for (JComponent component : List.of(name, location, language, browse, next, back, cancel)) component.setEnabled(!value);
    }

    private void chooseLocation() {
        JFileChooser chooser = new JFileChooser(location.getText());
        chooser.setDialogTitle("Escolher pasta do projeto");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) location.setText(chooser.getSelectedFile().toString());
    }

    private Path targetPath() { return Path.of(location.getText().trim()).resolve(name.getText().trim()).toAbsolutePath().normalize(); }

    private void updateDestination() {
        try { destination.setText(targetPath().toString()); }
        catch (Exception error) { destination.setText("Escolha a localização e o nome do projeto."); }
    }

    private static <T extends JComponent> T sized(T field) {
        field.putClientProperty("JComponent.roundRect", false);
        field.setPreferredSize(new Dimension(UiTokens.scale(160), UiTokens.scale(30)));
        field.setMinimumSize(new Dimension(UiTokens.scale(90), UiTokens.scale(30)));
        return field;
    }

    private static JLabel muted(String text) {
        JLabel label = new JLabel(text);
        label.setFont(UiTokens.fontSmall());
        label.setForeground(UiTokens.muted());
        return label;
    }

    private static JPanel transparent(LayoutManager layout) {
        JPanel panel = new JPanel(layout);
        panel.setOpaque(false);
        return panel;
    }

    private static JComponent section(String title, String subtitle, JComponent content) {
        CardPanel card = new CardPanel(title, subtitle);
        card.setVariant(CardPanel.Variant.FILLED);
        card.setArc(UiTokens.radius(UiTokens.Radius.MD));
        card.setPadding(new Insets(UiTokens.space(3), UiTokens.space(3), UiTokens.space(3), UiTokens.space(3)));
        card.setContent(content);
        JPanel holder = transparent(new BorderLayout());
        holder.add(card, BorderLayout.NORTH);
        return holder;
    }

    private static JComponent scroll(JComponent content) {
        JPanel viewport = new Viewport(content);
        ScrollPanel scroll = new ScrollPanel(viewport);
        scroll.setHorizontalScrollEnabled(false).setUnitIncrement(UiTokens.scale(16));
        return scroll;
    }

    private static final class Viewport extends JPanel implements Scrollable {
        private Viewport(JComponent content) {
            super(new BorderLayout());
            setOpaque(false);
            add(content, BorderLayout.NORTH);
        }
        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) { return UiTokens.scale(16); }
        @Override public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) { return visible.height; }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }
}
