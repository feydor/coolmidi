package io.feydor.ui.impl;

import io.feydor.midi.Midi;
import io.feydor.ui.MidiController;
import io.feydor.ui.MidiUi;

public class MidiTrackerUi implements MidiUi {

    @Override
    public void block(Midi midi, MidiController midiController) throws Exception {
        double t = 0;
        int maxMsgLen = 6;
        var remainingTime = midiController.getCurrentRemainingTime();
        int totalTimeDigits = String.valueOf(Math.round(remainingTime.ms())).length();
        var eventBatches = midi.allEventsInAbsoluteTime();
        for (var eventBatch : eventBatches) {
            // print the events on one line
            var sb = new StringBuilder(String.format("%0" + totalTimeDigits + "d ", Math.round(t)));
            for (var event : eventBatch) {
                int spaces = maxMsgLen - event.message.length();
                spaces = Math.max(spaces, 0);
                sb.append("| ").append(event.message).append(" ".repeat(spaces+1));
            }
            sb.append("|");
            System.out.println(sb);

            // sleep for the absolute time - current time
            // this is used to synchronize this UI thread with the playback thread
            double dt = Math.abs(t - eventBatch.get(0).absoluteTime);
            Thread.sleep((long) dt);
            t = eventBatch.get(0).absoluteTime;
        }
    }
}
