package io.feydor;

import io.feydor.midi.Midi;
import io.feydor.ui.*;

import javax.sound.midi.MidiSystem;
import javax.sound.midi.MidiUnavailableException;
import java.io.File;
import java.io.IOException;
import java.util.*;

enum MidiCliOption {
    NO_UI,
    TRACKER_UI,
    TUI_UI,
    STATUS_LINE_UI
}

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

        List<File> files = new ArrayList<>();
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
                case "-v", "--verbose" -> verbose = true;
                case "-l", "--loop" -> loop = true;
                default -> files.addAll(parseFiles(arg));
            }
        }

        MidiCliPlayer player = new MidiCliPlayer(files, uiOption, verbose);
        player.playAndBlock(loop);
    }

    public MidiCliPlayer(List<File> files, MidiCliOption uiOption, boolean verbose) throws MidiUnavailableException {
        // Filter out the invalid Midi files
        List<Midi> playlist = files.stream().map(file -> {
                    try {
                        return new Midi(file.getAbsolutePath(), verbose);
                    } catch (IOException e) {
                        System.err.printf("The file failed to load: %s\n%s. Skipping...\n", file.getAbsolutePath(), e.getMessage());
                        return null;
                    } catch (RuntimeException e) {
                        System.err.printf("The MIDI file failed to parse: %s\n%s. Skipping...\n", file.getAbsolutePath(), e.getMessage());
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .toList();

        if (!playlist.isEmpty()) {
            System.out.printf("Parsed %d MIDI files\n", playlist.size());
            playlist = new ArrayList<>(playlist);
            Collections.shuffle(playlist);
        }

        // Get the default MIDI device and its receiver
        if (verbose) {
            var devices = MidiSystem.getMidiDeviceInfo();
            System.out.println("# of devices: " + devices.length);
            System.out.println("Available devices: " + Arrays.toString(devices));
        }

        MidiUi ui = switch (uiOption) {
            case TUI_UI -> new MidiTuiUi();
            case TRACKER_UI -> new MidiTrackerUi();
            case STATUS_LINE_UI -> new MidiStatusLineUi();
            case NO_UI -> null;
        };

        this.midiScheduler = new MidiScheduler(ui, playlist, MidiSystem.getReceiver(), verbose);
    }

    public void playAndBlock(boolean loop) throws Exception {
        midiScheduler.scheduleEventsAndWait(loop);
    }

    private static List<File> parseFiles(String filename) {
        if (!filename.isBlank() && filename.charAt(0) != '-') {
            File file = new File(filename);
            if (file.isDirectory()) {
                System.out.println("Found dir: " + file.getAbsolutePath());
                File[] dirFiles = file.listFiles((dir, name) -> name.toLowerCase().matches("^.*\\.(midi|mid)$"));
                if (dirFiles != null)
                    return List.of(dirFiles);
            } else {
                return List.of(file);
            }
        }
        return List.of();
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
