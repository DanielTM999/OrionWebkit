package dtm.ide.react;

import dtm.ide.api.extension.wizard.ProjectWizardCallback;
import dtm.stools.component.feedback.steps.StepsPanel;
import dtm.stools.component.form.FormField;
import dtm.stools.component.inputfields.textfield.MaskedTextField;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.swing.*;
import java.awt.*;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class ReactProjectWizardViewTest {
    @TempDir Path root;

    @Test
    void rejectsInvalidNamesBeforeReviewAndReturnsToEditing() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var wizard = new ReactProjectWizardProvider().getProjectWizards().iterator().next();
            assertNotNull(wizard.getIcon());
            assertNotNull(new ReactRunContribution().getIcon());
            JPanel view = wizard.getView(null);
            assertEquals(Boolean.TRUE, view.getClientProperty("orion.wizard.fitViewportHeight"));
            StepsPanel steps = find(view, StepsPanel.class, null);
            MaskedTextField name = (MaskedTextField) find(view, FormField.class, "name").getControl();
            name.setText("../outside");
            button(view, "Avançar").doClick();
            assertEquals(0, steps.getCurrentStep());
            name.setText("my-app");
            button(view, "Avançar").doClick();
            assertEquals(1, steps.getCurrentStep());
            button(view, "Voltar").doClick();
            assertEquals(0, steps.getCurrentStep());
        });
    }

    @Test
    void createsReviewedProjectInBackgroundAndNotifiesOnEdt() throws Exception {
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<Path> created = new AtomicReference<>();
        AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            var view = new ReactProjectWizardView(new ProjectWizardCallback() {
                @Override public void notifyProjectCreated(Path path) {
                    try {
                        assertTrue(SwingUtilities.isEventDispatchThread());
                        created.set(path);
                    } catch (Throwable error) { callbackFailure.set(error); }
                    finally { completed.countDown(); }
                }
                @Override public void cancel() { fail("Unexpected cancellation"); }
            });
            ((MaskedTextField) find(view, FormField.class, "name").getControl()).setText("reviewed-app");
            for (Component component : descendants(find(view, FormField.class, "location"))) {
                if (component instanceof MaskedTextField field) field.setText(root.toString());
            }
            button(view, "Avançar").doClick();
            button(view, "Criar projeto").doClick();
        });
        assertTrue(completed.await(10, TimeUnit.SECONDS));
        assertNull(callbackFailure.get());
        assertEquals(root.resolve("reviewed-app"), created.get());
        assertTrue(Files.isRegularFile(created.get().resolve("src/App.tsx")));
    }

    private static JButton button(Container view, String text) {
        return descendants(view).stream().filter(component -> component instanceof JButton button && text.equals(button.getText()))
                .map(JButton.class::cast).findFirst().orElseThrow();
    }

    private static <T extends Component> T find(Container view, Class<T> type, String fieldName) {
        return descendants(view).stream().filter(type::isInstance).map(type::cast)
                .filter(component -> fieldName == null || component instanceof FormField field && fieldName.equals(field.getFieldName()))
                .findFirst().orElseThrow();
    }

    private static java.util.List<Component> descendants(Container view) {
        java.util.List<Component> result = new java.util.ArrayList<>();
        for (Component component : view.getComponents()) {
            result.add(component);
            if (component instanceof Container child) result.addAll(descendants(child));
        }
        return result;
    }
}
