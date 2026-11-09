package dtm.ide.react;

import dtm.stools.utils.ImageUtils;
import javax.swing.Icon;

public final class ReactIcons {
    private ReactIcons() { }

    public static Icon react(int size) {
        return ImageUtils.getIconByResource(ReactIcons.class, "/imgs/react.svg")
                .map(icon -> ImageUtils.resizeIcon(icon, size, size)).orElse(null);
    }
}
