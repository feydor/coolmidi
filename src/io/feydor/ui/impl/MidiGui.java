package io.feydor.ui.impl;

import io.feydor.midi.Midi;
import io.feydor.midi.MidiChannel;
import io.feydor.ui.MidiController;
import io.feydor.ui.MidiUi;
import io.feydor.util.JsonIo;

import javax.swing.*;
import javax.swing.border.BevelBorder;
import java.awt.*;
import java.io.File;
import java.util.List;
import java.util.Map;

public class MidiGui implements MidiUi {
    private JFrame frame;
//    private MidiChannel[] channels;
    private JMenuBar[] programMenus;
    private MidiController midiController;
    private volatile boolean isListeningForChannelEvents;
    private static final int DROPDOWN_CHAR_WIDTH = 44;

    @Override
    public void block(Midi midi, MidiController midiController) {
        this.midiController = midiController;
        if (frame != null)
            return;

        initLookAndFeel();
        frame = new JFrame("CMIDI");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        frame.setSize(300,800);

        var channels = midiController.getChannels();
        JPanel mainPanel = new JPanel(new GridLayout(channels.length + 3, 1, 25, 0));

        // First column, channel controls
        this.programMenus = new JMenuBar[channels.length];
        for (int i = 0; i < channels.length; ++i) {
            JPanel rowPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
            rowPanel.add(createMuteButton(channels[i]));
            programMenus[i] = createProgramMenu(channels[i]);
            rowPanel.add(programMenus[i]);
            rowPanel.add(new JSlider(JSlider.HORIZONTAL, 0, 100, 50)); // TODO: volume slider
            mainPanel.add(rowPanel);
        }

        // Second column, misc sliders
        mainPanel.add(new JLabel(String.format("Tempo %.2f (ms/tick), %d bpm", midi.msPerTick(),
                60_000_000 / midi.getTracks().get(0).getTempo())));
//        mainPanel.add(new JLabel("Song Length"));
//        mainPanel.add(new JSlider(JSlider.HORIZONTAL, 0, 100, 0));
        var checkbox = new JCheckBox("Loop?");
        checkbox.addActionListener(event -> midiController.toggleCurrentMidiLooping());
        mainPanel.add(checkbox);
        var songBar = new JTextField((new File(midi.filename).getName()));
        songBar.setEditable(false);
        songBar.setBorder(new BevelBorder(BevelBorder.LOWERED));
        mainPanel.add(songBar);

        frame.add(mainPanel);
        frame.setVisible(true);

        if (!isListeningForChannelEvents)
            spawnChannelEventListenerThread();
    }

    private JToggleButton createMuteButton(MidiChannel channel) {
        var button = new JToggleButton(String.format("%02d", channel.channel));
        if (!channel.used)
            button.setEnabled(false);
        button.addActionListener(e -> {
            if (button.isSelected()) {
                midiController.addChannelVolumeEvent((byte)(channel.channel - 1), (byte)0x00);
            } else {
                midiController.addChannelVolumeEvent((byte)(channel.channel - 1), (byte)127); // TODO: Get initial volume from channel
            }
        });
        return button;
    }

    @SuppressWarnings("rawtypes")
    private JMenuBar createProgramMenu(MidiChannel midiChannel) {
        Map<String, Object> menuMap = JsonIo.getGmMidiJsonStringMap();
        JMenuBar menuBar = new JMenuBar();
        String initialInstrumentName = midiChannel.used ? midiChannel.getCurrentGmProgramName() : " ".repeat(DROPDOWN_CHAR_WIDTH);
        JMenu rootMenu = new JMenu(initialInstrumentName);
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
                            newProgramName = newProgramName + " ".repeat(DROPDOWN_CHAR_WIDTH - newProgramName.length());
                            programMenus[event.channel().channel - 1].getMenu(0).setText(newProgramName);
                        }
                    }
                }
            }
        }).start();
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
