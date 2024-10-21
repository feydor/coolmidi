package io.feydor.ui.impl;

import io.feydor.midi.Midi;
import io.feydor.midi.MidiChannel;
import io.feydor.ui.MidiController;
import io.feydor.ui.MidiUi;
import io.feydor.ui.impl.gui.PianoRollModel;
import io.feydor.util.FileIo;

import javax.swing.*;
import javax.swing.border.BevelBorder;
import javax.swing.border.EtchedBorder;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

public class MidiGui implements MidiUi {
    private JFrame frame;
    private JMenuBar[] programMenus;
    private PianoRollModel[] pianoRollModels;
    private JToggleButton[] muteButtons;
    private JLabel tempoLabel;
    private JFileChooser fileChooser;
    private JTextField songBar;
    private MidiController midiController;
    private volatile boolean isListeningForChannelEvents;

    public MidiGui() {
        initLookAndFeel();
    }

    @Override
    public void block(Midi midi, MidiController midiController) {
        this.midiController = midiController;
        if (frame != null) {
            refreshComponentState(midi);
            return;
        }

        frame = new JFrame("CMIDI");
        frame.setSize(300,800);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        var channels = midiController.getChannels();
        JPanel mainPanel = new JPanel();
        mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));// GridLayout(3, 1, 1, 1)
        mainPanel.setBorder(new BevelBorder(BevelBorder.RAISED));

        // top panel, file controls
        mainPanel.add(createFileMenu());
        mainPanel.add(Box.createVerticalGlue());

        // middle panel, channel info
        var middlePanel = new JPanel(new GridLayout(channels.length + 2, 1, 0, 0));
        middlePanel.setPreferredSize(new Dimension(200, 550));
        middlePanel.setMaximumSize(new Dimension(Integer.MAX_VALUE - 1, Integer.MAX_VALUE - 1));
        middlePanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
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
            progBar.setMaximumSize(new Dimension(100, 100));
            rowPanel.add(progBar);

            middlePanel.add(rowPanel);
        }

        tempoLabel = new JLabel(String.format("Tempo: %.2f (ms/tick), %d bpm", midi.msPerTick(),
                60_000_000 / midi.getTracks().get(0).getTempo()));
        middlePanel.add(tempoLabel);

        var controlPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        controlPanel.add(createPlayButton());
        var checkbox = new JCheckBox("Loop?");
        checkbox.addActionListener(event -> midiController.toggleCurrentMidiLooping());
        controlPanel.add(checkbox);
        middlePanel.add(controlPanel);

        mainPanel.add(middlePanel);

        // bottom panel, song name bar
        songBar = new JTextField("Now playing: " + (new File(midi.filename)).getName());
        songBar.setEditable(false);
        songBar.setBorder(new BevelBorder(BevelBorder.LOWERED));
        songBar.setMaximumSize(new Dimension(Integer.MAX_VALUE - 1, 30));
        mainPanel.add(Box.createVerticalGlue());
        mainPanel.add(songBar);

        frame.add(mainPanel);
        frame.setVisible(true);

        if (!isListeningForChannelEvents)
            spawnChannelEventListenerThread();
    }

    private void refreshComponentState(Midi midi) {
        songBar.setText("Now playing: " + (new File(midi.filename)).getName());
    }

    private JPanel createFileMenu() {
        fileChooser = new JFileChooser();
        var openButton = new JButton("Open a File...", FileIo.createImageIcon("images/Open16.gif"));
        openButton.setMaximumSize(new Dimension(100, 100));
        openButton.addActionListener(this::handleOpenFileChooser);
//        var saveButton = new JButton("Save a File...", FileIo.createImageIcon("images/Save16.gif"));
//        saveButton.addActionListener(a -> handleSaveFileChooser(a));
        var buttonPanel = new JPanel();
        buttonPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE - 1, 33));
        buttonPanel.setLayout(new BoxLayout(buttonPanel, BoxLayout.X_AXIS));
        buttonPanel.add(openButton);
//        buttonPanel.add(saveButton);
        buttonPanel.setBorder(new EtchedBorder());
        return buttonPanel;
    }

    public void handleOpenFileChooser(ActionEvent e) {
        int returnVal = fileChooser.showOpenDialog(frame);
        if (returnVal == JFileChooser.APPROVE_OPTION) {
            File file = fileChooser.getSelectedFile();
            System.out.println("Opening: " + file.getAbsolutePath());
            try {
                midiController.replaceCurrentlyPlaying(file);
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
        } else {
            System.out.println("Open command cancelled by user");
        }
    }

    private JToggleButton createMuteButton(MidiChannel channel) {
        var button = new JToggleButton(String.format("%02d", channel.channel));
        button.setMaximumSize(new Dimension(55, 55));
        if (!channel.used)
            button.setEnabled(false);
        button.addActionListener(e -> {
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
        Map<String, Object> menuMap = FileIo.getGmMidiJsonStringMap();
        JMenuBar menuBar = new JMenuBar();
        menuBar.setPreferredSize(new Dimension(200, 200));
        String initialInstrumentName = midiChannel.used ? midiChannel.getCurrentGmProgramName() : "";
        JMenu rootMenu = new JMenu(initialInstrumentName);
        rootMenu.setPreferredSize(new Dimension(200, 200));
        menuBar.add(rootMenu);
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

    private JToggleButton createPlayButton() {
        var playButton = new JToggleButton("PLAYING");
        playButton.addActionListener(event -> {
            midiController.togglePlaying();
            if (playButton.isSelected()) {
                playButton.setText("PAUSED");
                for (JToggleButton muteButton : muteButtons)
                    muteButton.setSelected(true);
            } else {
                playButton.setText("PLAYING");
                for (JToggleButton muteButton : muteButtons)
                    muteButton.setSelected(false);
            }
        });
        return playButton;
    }

    private static void initLookAndFeel() {
        try {
            UIManager.setLookAndFeel("com.sun.java.swing.plaf.motif.MotifLookAndFeel");
        } catch (Exception ex) {
            System.err.println(ex.getLocalizedMessage());
            ex.printStackTrace(System.err);
        }
        JFrame.setDefaultLookAndFeelDecorated(true);
    }
}
