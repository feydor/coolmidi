package io.feydor.ui.impl;

import io.feydor.midi.Midi;
import io.feydor.midi.MidiChannel;
import io.feydor.ui.MidiController;
import io.feydor.ui.MidiUi;
import io.feydor.ui.Terminal;
import io.feydor.ui.TotalTime;

import java.util.Arrays;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

public class MidiChannelUi implements MidiUi {
    private static final int UPDATE_PERIOD = 100; // ms
    private static TotalTime remainingTime;

    @Override
    public void block(Midi midi, MidiController midiController) throws Exception {
        MidiChannelUi.remainingTime = midiController.getCurrentRemainingTime();
        var timer = new Timer();
        timer.scheduleAtFixedRate(new UiThread(timer, midiController.getChannels()), 0, UPDATE_PERIOD);

        Thread.sleep((long) remainingTime.ms() + 2000);

        timer.purge();
    }

    private static class UiThread extends TimerTask {
        private final Timer timerHandle;
        private final List<MidiChannel> usedChannels;
        private int time = 0;

        public UiThread(Timer timerHandle, MidiChannel[] channels) {
            this.timerHandle = timerHandle;
            this.usedChannels = Arrays.stream(channels).filter(ch -> ch.used).toList();
            System.out.println();
        }

        @Override
        public void run() {
            time += UPDATE_PERIOD;
            resetCursor();

            printChannelStates();

            if (time >= remainingTime.ms()) {
                timerHandle.cancel();
                System.out.println();
                System.out.println();
                System.out.flush();
            }
        }

        private void resetCursor() {
            Terminal.moveCursorUp(usedChannels.size());
            Terminal.carriageReturn();
        }

        private void printChannelStates() {
            for (MidiChannel channel : usedChannels) {
                System.out.println(channel);
            }
            System.out.flush();
        }
    }
}
