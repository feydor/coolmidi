package io.feydor;

import io.feydor.ui.*;
import io.feydor.ui.impl.gui.DefaultMidiGui;

import javax.sound.midi.MidiUnavailableException;
import java.io.File;


/**
 * Plays a list of MIDI files using the OS's default MIDI synthesizer and displays a CLI UI with the current notes
 * for up to the maximum 16 MIDI channels. Midi files play front beginning to end, in the order they were passed in.
 *
 * <p>Usage: java MidiCliPlayer file1.mid file2.mid</p>
 */
public final class MidiCliPlayer {
    private final MidiController midiController;

    enum MidiCliOption {
        NO_UI,
        TRACKER_UI,
        TUI_UI,
        CHANNEL_UI,
        STATUS_LINE_UI
    }

    public static void main(String[] args) throws Exception {
        File input = new File("does not exist");
        var uiOption = MidiCliOption.STATUS_LINE_UI;
        boolean verbose = false, loop = false;
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
                case "-A" -> uiOption = MidiCliOption.STATUS_LINE_UI;
                case "-B" -> uiOption = MidiCliOption.TRACKER_UI;
                case "-C" -> uiOption = MidiCliOption.TUI_UI;
                case "-D" -> uiOption = MidiCliOption.NO_UI;
                case "-E" -> uiOption = MidiCliOption.CHANNEL_UI;
                case "-v", "--verbose" -> verbose = true;
                case "-l", "--loop" -> loop = true;
                default -> input = new File(arg);
            }
        }

        MidiCliPlayer player = new MidiCliPlayer(input, uiOption, verbose);
        if (input.exists()) {
            player.midiController.loadMidiFile(input);
            player.midiController.initUi(null);
            player.midiController.startPlaybackFromBeginning();
            player.midiController.waitForInput();
        } else {
            player.midiController.initUi(null);
            player.midiController.waitForInput();
        }
    }

    public MidiCliPlayer(File file, MidiCliOption uiOption, boolean verbose) throws MidiUnavailableException {
        IMidiUi ui = new DefaultMidiGui();
        midiController = new MidiController(ui, verbose);
    }

    private static void printOptions() {
        String msg = "\nCOOL Midi\n\nUsage: cmidi [MIDI Files]\n\n";
        msg += "Options:\n";
        msg += "\n  -A   Use the status line UI (Default)";
        msg += "\n  -B   Use the alternative tracker-like UI";
        msg += "\n  -C   Use the TUI-like UI";
        msg += "\n  -D   Use no UI";
        msg += "\n  -V,--version   Print version information";
        msg += "\n  -H,--help      Print this message";
        msg += "\n  -v,--verbose   Print extra logs";
        System.out.println(msg);
    }

    private static void printVersion() {
        System.out.println("COOL Midi 0.1.0\nCopyright (C) 2023 feydor\n");
    }
}
