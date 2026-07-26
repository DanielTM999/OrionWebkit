package dtm.ide.ui;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.Icon;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.UIManager;
import javax.swing.border.AbstractBorder;
import javax.swing.event.AncestorEvent;
import javax.swing.event.AncestorListener;
import java.awt.AlphaComposite;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;

/** Styled dialog content for the Project Tree's New > Web file action. */
public final class NewWebItemPanel extends JPanel {

    public enum Kind {
        HTML("HTML page", "html", new Color(227, 76, 38), "H", "<!doctype html>\n<html lang=\"en\">\n<head>\n    <meta charset=\"UTF-8\">\n    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n    <title>%s</title>\n</head>\n<body>\n\n</body>\n</html>\n"),
        CSS("CSS stylesheet", "css", new Color(38, 77, 228), "#", ""),
        JAVASCRIPT("JavaScript file", "js", new Color(240, 219, 79), "J", ""),
        TYPESCRIPT("TypeScript file", "ts", new Color(49, 120, 198), "T", ""),
        JSX("React component", "jsx", new Color(97, 218, 251), "R", "export default function %s() {\n    return (\n        <div>%s</div>\n    );\n}\n"),
        TSX("React component", "tsx", new Color(49, 120, 198), "R", "export default function %s() {\n    return (\n        <div>%s</div>\n    );\n}\n"),
        VUE("Vue component", "vue", new Color(65, 184, 131), "V", "<script setup>\n\n</script>\n\n<template>\n  <div>%s</div>\n</template>\n\n<style scoped>\n\n</style>\n"),
        SVELTE("Svelte component", "svelte", new Color(255, 62, 0), "S", "<script>\n\n</script>\n\n<div>%s</div>\n\n<style>\n\n</style>\n");

        private final String label;
        private final String extension;
        private final Color color;
        private final String badge;
        private final String template;

        Kind(String label, String extension, Color color, String badge, String template) {
            this.label = label;
            this.extension = extension;
            this.color = color;
            this.badge = badge;
            this.template = template;
        }

        public String fileName(String name) {
            String suffix = "." + extension;
            return name.endsWith(suffix) ? name : name + suffix;
        }

        public String template(String name) {
            String component = componentName(name);
            return template.isEmpty() ? "" : template.formatted(component, component);
        }

        @Override
        public String toString() {
            return label + " (." + extension + ")";
        }
    }

    public record Result(String name, Kind kind) { }

    private final JTextField nameField = new JTextField();
    private final JList<Kind> kindList = new JList<>(Kind.values());

    public NewWebItemPanel() {
        super(new BorderLayout(0, 10));
        setOpaque(false);
        build();
        wire();
    }

    public Result getResult() {
        String name = sanitize(nameField.getText());
        Kind kind = kindList.getSelectedValue();
        return name.isEmpty() || kind == null ? null : new Result(name, kind);
    }

    private void build() {
        nameField.setToolTipText("File name without extension");
        nameField.setPreferredSize(new Dimension(360, 34));
        nameField.setBackground(inputBackground());
        nameField.setForeground(foreground());
        nameField.setCaretColor(foreground());
        nameField.setBorder(roundedBorder());
        nameField.setFont(nameField.getFont().deriveFont(14f));

        kindList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        kindList.setVisibleRowCount(Kind.values().length);
        kindList.setFixedCellHeight(32);
        kindList.setOpaque(false);
        kindList.setForeground(foreground());
        kindList.setBorder(BorderFactory.createEmptyBorder(2, 0, 0, 0));
        kindList.setCellRenderer(new KindRenderer());
        kindList.setSelectedValue(Kind.HTML, true);

        add(nameField, BorderLayout.NORTH);
        add(kindList, BorderLayout.CENTER);
    }

    private void wire() {
        addAncestorListener(new AncestorListener() {
            @Override public void ancestorAdded(AncestorEvent event) { nameField.requestFocusInWindow(); }
            @Override public void ancestorRemoved(AncestorEvent event) { }
            @Override public void ancestorMoved(AncestorEvent event) { }
        });
    }

    private static String sanitize(String raw) {
        if (raw == null) return "";
        StringBuilder value = new StringBuilder();
        for (char c : raw.trim().toCharArray()) {
            if (Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.') value.append(c);
        }
        return value.toString();
    }

    private static String componentName(String name) {
        String plain = name.replaceFirst("\\.[^.]+$", "");
        StringBuilder result = new StringBuilder();
        for (String part : plain.split("[-_\\s]+")) {
            if (!part.isEmpty()) result.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return result.isEmpty() ? "Component" : result.toString();
    }

    private final class KindRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean selected, boolean focused) {
            super.getListCellRendererComponent(list, value, index, selected, focused);
            Kind kind = (Kind) value;
            setText(kind.label + "  (." + kind.extension + ")");
            setIcon(new BadgeIcon(kind.color, kind.badge));
            setIconTextGap(10);
            setBorder(BorderFactory.createEmptyBorder(4, 10, 4, 10));
            setFont(getFont().deriveFont(13f));
            setForeground(selected ? selectionForeground() : foreground());
            setBackground(selected ? selectionBackground() : new Color(0, 0, 0, 0));
            setOpaque(selected);
            return this;
        }
    }

    private static final class BadgeIcon implements Icon {
        private static final int SIZE = 18;
        private final Color color;
        private final String label;

        BadgeIcon(Color color, String label) {
            this.color = color;
            this.label = label;
        }

        @Override
        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D g2 = (Graphics2D) graphics.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(color);
                g2.fillRoundRect(x, y, SIZE, SIZE, 6, 6);
                g2.setColor(new Color(20, 20, 20, 220));
                g2.setFont(component.getFont().deriveFont(Font.BOLD, 11f));
                var metrics = g2.getFontMetrics();
                int tx = x + (SIZE - metrics.stringWidth(label)) / 2;
                int ty = y + (SIZE - metrics.getHeight()) / 2 + metrics.getAscent();
                g2.drawString(label, tx, ty);
            } finally {
                g2.dispose();
            }
        }

        @Override public int getIconWidth() { return SIZE; }
        @Override public int getIconHeight() { return SIZE; }
    }

    private static Color inputBackground() {
        Color color = UIManager.getColor("TextField.background");
        return color != null ? color : new Color(60, 63, 65);
    }

    private static Color foreground() {
        Color color = UIManager.getColor("Label.foreground");
        return color != null ? color : new Color(220, 220, 220);
    }

    private static Color selectionBackground() {
        Color color = UIManager.getColor("List.selectionBackground");
        return color != null ? color : new Color(59, 130, 246);
    }

    private static Color selectionForeground() {
        Color color = UIManager.getColor("List.selectionForeground");
        return color != null ? color : Color.WHITE;
    }

    private static AbstractBorder roundedBorder() {
        Color color = UIManager.getColor("Component.borderColor");
        if (color == null) color = UIManager.getColor("Separator.foreground");
        Color stroke = color != null ? color : new Color(90, 90, 90);
        return new AbstractBorder() {
            @Override
            public void paintBorder(Component component, Graphics graphics, int x, int y, int width, int height) {
                Graphics2D g2 = (Graphics2D) graphics.create();
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(stroke);
                    g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.7f));
                    g2.drawRoundRect(x, y, width - 1, height - 1, 8, 8);
                } finally {
                    g2.dispose();
                }
            }

            @Override
            public Insets getBorderInsets(Component component) {
                return new Insets(7, 10, 7, 10);
            }
        };
    }
}
