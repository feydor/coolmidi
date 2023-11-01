package io.feydor.ui;

import io.feydor.midi.Midi;

import java.util.Map;
import java.util.concurrent.Future;

public class MidiTrackerUi implements MidiUi {

    @Override
    public void block(Midi midi, Future<Void> playbackThread, Map<Integer, Integer> channels) throws Exception {
        double t = 0;
        int maxMsgLen = 6;
        var eventBatches = midi.allEventsInAbsoluteTime();
        for (var eventBatch : eventBatches) {
            // print the events on one line
            var sb = new StringBuilder();
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
