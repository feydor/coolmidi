package io.feydor.ui.impl.gui;

import javax.swing.*;

public class PianoRollModel extends DefaultBoundedRangeModel {
    public PianoRollModel() {
        setMaximum(127);
        setMinimum(0);
    }
}
