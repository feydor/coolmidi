package io.feydor.ui;

import io.feydor.midi.Midi;

public interface IMidiUi {
    void initialize(Midi currentlyPlaying, MidiController midiController);
}
