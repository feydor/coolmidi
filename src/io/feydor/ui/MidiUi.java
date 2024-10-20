package io.feydor.ui;

import io.feydor.midi.Midi;
import io.feydor.midi.MidiChannel;
import io.feydor.ui.impl.MidiUiEventListener;

import java.util.concurrent.Future;

public interface MidiUi {
    /**
     * Runs a UI in the current thread and blocks
     * @param midi The currently playing midi. Used to access statistics.
     * @param midiController Used to interface between UI and player
     */
    void block(Midi midi, MidiController midiController) throws Exception;
}
