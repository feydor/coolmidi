package io.feydor.ui;

import io.feydor.midi.Midi;

import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.DoubleAccumulator;

public class MidiStatusLineUi implements MidiUi {
    private static final String BLOCK = " ";
    private static final int SEGMENTS = 30;
    private static final int UPDATE_PERIOD = 1000; // ms
    private static final String BG_GRN_ON = "\033[42m";
    private static final String BG_WHITE_ON = "\033[47m";
    private static final String BG_OFF = "\033[0m";

    @Override
    public void block(Midi midi, Future<Void> playbackThread, Map<Integer, Integer> channels) throws Exception {
        var t = new DoubleAccumulator((acc, x) -> x, 0);
        var eventBatches = midi.allEventsInAbsoluteTime();
        TotalTime totalTime = new TotalTime(eventBatches.get(eventBatches.size()-1).get(0).absoluteTime);

        var timer = new Timer();
        timer.scheduleAtFixedRate(new TimerTask() {
            private int curSegment = 0;
            private int millisec = 0;
            private final double msPerSegment = totalTime.ms / SEGMENTS;

            @Override
            public void run() {
                millisec += UPDATE_PERIOD;
                carriageReturn();
                printStatusLine();

                if (millisec >= curSegment * msPerSegment) {
                    curSegment++;
                }

                if (millisec >= totalTime.ms) {
                    timer.cancel();
                    System.out.println();
                    clearFromCursor();
                    curSegment = 0;
                }
            }

            private void printStatusLine() {
                TotalTime cur = new TotalTime(millisec);
                System.out.printf("%s %s%s%s%s%s%s %s", cur, BG_GRN_ON, BLOCK.repeat(curSegment), BG_OFF,
                        BG_WHITE_ON, BLOCK.repeat(SEGMENTS - curSegment), BG_OFF, totalTime);
                System.out.flush();
            }
        }, 0, UPDATE_PERIOD);

        for (var eventBatch : eventBatches) {
            // sleep for the absolute time - current time
            // this is used to synchronize this UI thread with the playback thread
            double dt = Math.abs(t.doubleValue() - eventBatch.get(0).absoluteTime);
            Thread.sleep((long) dt);
            t.accumulate(eventBatch.get(0).absoluteTime);
        }

        Thread.sleep(UPDATE_PERIOD);
        timer.purge();
    }

    private static void carriageReturn() {
        System.out.print("\r");
        System.out.flush();
    }

    private static void clearFromCursor() {
        System.out.print("\033[K");
        System.out.flush();
    }

    record TotalTime(double ms) {
        @Override
        public String toString() {
            double remainingMs = ms;
            int hr = 0, min = 0, sec;

            if (ms / 1000 / 60 / 60 >= 1) {
                hr = (int)ms / 1000 / 60 / 60;
                remainingMs -= hr * 60 * 60 * 1000;
            }

            if (remainingMs / 1000 / 60 > 0) {
                min = (int) remainingMs / 1000 / 60;
                remainingMs -= min * 60 * 1000;
            }

            sec = (int) remainingMs / 1000;

            return String.format("%02d:%02d:%02d", hr, min, sec);
        }
    }
}
