package io.feydor.ui;

import io.feydor.midi.Midi;

import java.util.Map;
import java.util.concurrent.Future;

public interface MidiUi {
    /**
     * Runs a UI in the current thread and blocks
     * @param midi The currently playing midi. Used to access statistics.
     * @param playbackThread The currently playing thread's future
     * @param channels A map from midi channel # to that channel's current value
     */
    void block(Midi midi, Future<Void> playbackThread, Map<Integer, Integer> channels) throws Exception;
}
