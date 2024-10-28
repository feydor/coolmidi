package io.feydor.ui.impl.gui;

import io.feydor.midi.Midi;
import io.feydor.midi.MidiChannel;
import io.feydor.ui.MidiController;
import io.feydor.util.FileIo;

import javax.swing.*;
import javax.swing.border.BevelBorder;
import java.awt.*;
import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public class PlayingMidiPanel extends JPanel {
    private final MidiController midiController;
    private JMenuBar[] programMenus;
    private PianoRollModel[] pianoRollModels;
    private JToggleButton[] muteButtons;
    private JLabel tempoLabel;
    private JTextField songBar;
    private volatile boolean isListeningForChannelEvents;

    public PlayingMidiPanel(Midi currentlyPlaying, MidiController midiController, Consumer<File> fileHandler) {
        this.midiController = midiController;
        setTransferHandler(new DragNDropFileTransferHandler(fileHandler));
        var channels = midiController.getChannels();

        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(new BevelBorder(BevelBorder.RAISED));

        // middle panel, channel info
        var middlePanel = new JPanel(new GridLayout(channels.length + 1, 1, 0, 0));
        middlePanel.setPreferredSize(new Dimension(200, 450));
        middlePanel.setMaximumSize(new Dimension(Integer.MAX_VALUE - 1, Integer.MAX_VALUE - 1));
        middlePanel.setBorder(BorderFactory.createEmptyBorder(5, 10, 0, 10));
        this.programMenus = new JMenuBar[channels.length];
        this.pianoRollModels = new PianoRollModel[channels.length];
        this.muteButtons = new JToggleButton[channels.length];
        for (int i = 0; i < channels.length; ++i) {
            JPanel rowPanel = new JPanel();
            rowPanel.setLayout(new BoxLayout(rowPanel, BoxLayout.X_AXIS));
            muteButtons[i] = createMuteButton(channels[i]);
            rowPanel.add(muteButtons[i]);
            programMenus[i] = createProgramMenu(channels[i]);
            rowPanel.add(programMenus[i]);

            pianoRollModels[i] = new PianoRollModel();
            var progBar = new JProgressBar(pianoRollModels[i]);
            progBar.setMaximumSize(new Dimension(90, 100));
            rowPanel.add(progBar);

            middlePanel.add(rowPanel);
        }

        tempoLabel = new JLabel(String.format("Tempo: %.2f (ms/tick), %d bpm", currentlyPlaying.msPerTick(),
                60_000_000 / currentlyPlaying.getTracks().get(0).getTempo()));
        middlePanel.add(tempoLabel);

        add(middlePanel);

        // bottom panel, song name bar
        songBar = new JTextField("Now playing: " + (new File(currentlyPlaying.filename)).getName());
        songBar.setEditable(false);
        songBar.setBorder(new BevelBorder(BevelBorder.LOWERED));
        songBar.setMaximumSize(new Dimension(Integer.MAX_VALUE - 1, 30));
        add(songBar);

        if (!isListeningForChannelEvents)
            spawnChannelEventListenerThread();
    }

    private JToggleButton createMuteButton(MidiChannel channel) {
        var button = new JToggleButton(String.format("%02d", channel.channel));
        button.setMaximumSize(new Dimension(55, 55));
        button.setFocusPainted(false);
        if (!channel.used)
            button.setEnabled(false);
        button.addActionListener(_ -> {
            if (!midiController.isPlaying()) {
                button.setSelected(true);
                return;
            }
            if (button.isSelected()) {
                midiController.addChannelVolumeEvent((byte)(channel.channel - 1), (byte)0x00);
            } else {
                midiController.addChannelVolumeEvent((byte)(channel.channel - 1), channel.getLastVolume());
            }
        });
        return button;
    }

    @SuppressWarnings("rawtypes")
    private JMenuBar createProgramMenu(MidiChannel midiChannel) {
        JMenuBar menuBar = new JMenuBar();
        menuBar.setPreferredSize(new Dimension(180, 200));
        String initialInstrumentName = midiChannel.used ? midiChannel.getCurrentGmProgramName() : "";
        JMenu rootMenu = new JMenu(initialInstrumentName);
        rootMenu.setPreferredSize(new Dimension(180, 200));
        menuBar.add(rootMenu);
        Map<String, Object> menuMap = FileIo.getGmMidiJsonStringMapFromResources();
        for (var entrySet : menuMap.entrySet()) {
            String groupName = entrySet.getKey();
            JMenu groupMenu = new JMenu(groupName);
            if (entrySet.getValue() instanceof List instrNames) {
                for (Object name : instrNames) {
                    var menuItem = new JMenuItem(String.valueOf(name));
                    menuItem.addActionListener(e -> {
                        JMenuItem thisMenuItem = (JMenuItem) e.getSource();
                        rootMenu.setText(thisMenuItem.getText());
                        midiController.addProgramChangeEvent((byte) (midiChannel.channel - 1), MidiChannel.gmProgramNameToByte(thisMenuItem.getText()));
                    });
                    groupMenu.add(menuItem);
                }
            } else {
                throw new RuntimeException("The JSON value was not a List!: " + entrySet.getValue() + ", but was " + entrySet.getValue().getClass());
            }
            rootMenu.add(groupMenu);
        }

        return menuBar;
    }

    private void spawnChannelEventListenerThread() {
        isListeningForChannelEvents = true;
        new Thread(() -> {
            while (isListeningForChannelEvents) {
                MidiController.MidiChannelEvent event = midiController.listenForMidiChannelEvent();
                switch (event.eventSubType()) {
                    case PROGRAM_CHANGE -> {
                        String newProgramName = event.channel().getCurrentGmProgramName();
                        String oldProgramName = programMenus[event.channel().channel - 1].getMenu(0).getText();
                        if (!newProgramName.equals(oldProgramName)) {
                            programMenus[event.channel().channel - 1].getMenu(0).setText(newProgramName);
                        }
                    }
                    case NOTE_ON -> {
                        byte noteVal = event.channel().note;
                        pianoRollModels[event.channel().channel - 1].setValue(noteVal & 0xff);
                    }
                    case NOTE_OFF -> pianoRollModels[event.channel().channel - 1].setValue(0);
                    case SET_TEMPO -> {
                        String newTempoText = String.format("Tempo: %.2f (ms/tick), %d bpm",
                                midiController.getCurrentlyPlaying().msPerTick(),
                                midiController.getCurrentlyPlaying().bpm());
                        if (!newTempoText.equals(tempoLabel.getText()))
                            tempoLabel.setText(newTempoText);
                    }
                }
            }
        }).start();
    }
}
