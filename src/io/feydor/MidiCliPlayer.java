package io.feydor;

import io.feydor.midi.Midi;
import io.feydor.ui.MidiScheduler;
import io.feydor.ui.MidiTrackerUi;
import io.feydor.ui.MidiTuiUi;
import io.feydor.ui.MidiUi;

import javax.sound.midi.MidiSystem;
import javax.sound.midi.MidiUnavailableException;
import javax.sound.midi.Receiver;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Plays a list of MIDI files using the OS's default MIDI synthesizer and displays a CLI UI with the current notes
 * for up to the maximum 16 MIDI channels. Midi files play front beginning to end, in the order they were passed in.
 *
 * <p>Usage: java MidiCliPlayer file1.mid file2.mid</p>
 */
public final class MidiCliPlayer {
    private final MidiScheduler midiScheduler;

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args[0].isBlank()) {
            printOptions();
            System.exit(1);
            return;
        }

        var uiOption = MidiCliOption.NO_UI;
        for (var arg : args) {
            switch (arg) {
                case "-V", "--version" -> {
                    printVersion();
                    System.exit(1);
                    return;
                }
                case "-H", "--help", "-h" -> {
                    printOptions();
                    System.exit(1);
                    return;
                }
                case "-A" -> uiOption = MidiCliOption.TRACKER_UI;
                case "-B" -> uiOption = MidiCliOption.TUI_UI;
                case "-C" -> uiOption = MidiCliOption.NO_UI;
            }
        }

        MidiCliPlayer player = new MidiCliPlayer(args, uiOption);
        player.playAndBlock();
    }

    public MidiCliPlayer(String[] filenames, MidiCliOption uiOption) throws MidiUnavailableException {
        // Filter out the invalid Midi files
        List<Midi> playlist = Stream.of(filenames)
                .filter(arg -> !arg.isBlank() && arg.charAt(0) != '-')
                .map(filename -> {
                    try {
                        return new Midi(filename.replaceAll("/.//", ""));
                    } catch (IOException e) {
                        System.out.printf("The file failed to load: %s\n%s. Skipping...", filename, e.getMessage());
                        return null;
                    }
                }).filter(Objects::nonNull).toList();

        // Get the default MIDI device and its receiver
        var devices = MidiSystem.getMidiDeviceInfo();
        System.out.println("# of devices: " + devices.length);
        System.out.println("Available devices: " + Arrays.toString(devices));
        Receiver receiver = MidiSystem.getReceiver();
        MidiUi ui = switch (uiOption) {
            case TUI_UI -> new MidiTuiUi();
            case TRACKER_UI -> new MidiTrackerUi();
            case NO_UI -> null;
        };

        this.midiScheduler = new MidiScheduler(ui, playlist, receiver);
    }

    public void playAndBlock() throws Exception {
        midiScheduler.scheduleEventsAndWait();
    }

    private static void printOptions() {
        String msg = "\nCOOL Midi\n\nUsage: cmidi [MIDI Files]\n\n";
        msg += "Options:\n  -V,--version   Print version information";
        msg += "\n  -H,--help      Print this message";
        msg += "\n  -A   Use the TUI-like UI";
        msg += "\n  -B   Use the alternative tracker-like UI";
        msg += "\n  -C   Use no UI (Default)";
        System.out.println(msg);
    }

    private static void printVersion() {
        String msg = "COOL Midi 0.1.0\nCopyright (C) 2023 feydor\n";
        System.out.println(msg);
    }
}
