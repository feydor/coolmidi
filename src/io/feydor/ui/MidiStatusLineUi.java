package io.feydor.ui;

import io.feydor.midi.Midi;
import io.feydor.midi.MidiChannel;

import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Future;

public class MidiStatusLineUi implements MidiUi {
    private static final String BLOCK = " ";
    private static final int SEGMENTS = 30;
    private static final int UPDATE_PERIOD = 1000; // ms
    private static final String BG_GRN_ON = "\033[42m";
    private static final String BG_WHITE_ON = "\033[47m";
    private static final String BG_OFF = "\033[0m";

    @Override
    public void block(Midi midi, Future<Void> playbackThread, MidiChannel[] channels, TotalTime remainingTime) throws Exception {
        var timer = new Timer();
        timer.scheduleAtFixedRate(new UiThread(remainingTime, timer), 0, UPDATE_PERIOD);

        Thread.sleep((long) remainingTime.ms() + 2000);

        timer.purge();
    }

    private static class UiThread extends TimerTask {
        private int curSegment = 0;
        private int millisec = 0;
        private final TotalTime remainingTime;
        private final double msPerSegment;
        private final Timer timerHandle;

        public UiThread(TotalTime remainingTime, Timer timerHandle) {
            this.remainingTime = remainingTime;
            this.msPerSegment = remainingTime.ms() / SEGMENTS;
            this.timerHandle = timerHandle;
        }

        @Override
        public void run() {
            millisec += UPDATE_PERIOD;
            Terminal.carriageReturn();
            printStatusLine();

            if (millisec >= curSegment * msPerSegment) {
                curSegment++;
            }

            if (millisec >= remainingTime.ms()) {
                timerHandle.cancel();
                System.out.println();
                Terminal.clearFromCursor();
                curSegment = 0;
            }
        }

        private void printStatusLine() {
            TotalTime cur = new TotalTime(millisec);
            System.out.printf("%s %s%s%s%s%s%s %s", cur, BG_GRN_ON, BLOCK.repeat(curSegment), BG_OFF,
                    BG_WHITE_ON, BLOCK.repeat(SEGMENTS - curSegment), BG_OFF, remainingTime);
            System.out.flush();
        }
    }
}
