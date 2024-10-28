package io.feydor.ui.impl.gui;

import javax.swing.*;
import java.io.File;
import java.util.function.Consumer;

public class NoMidiPanel extends JPanel {
    public NoMidiPanel(Consumer<File> fileHandler) {
        add(new JLabel("Drag and drop a midi file"));
        setTransferHandler(new DragNDropFileTransferHandler(fileHandler));
    }
}
