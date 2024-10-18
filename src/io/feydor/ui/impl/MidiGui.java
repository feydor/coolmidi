package io.feydor.ui.impl;

import com.formdev.flatlaf.FlatIntelliJLaf;
import io.feydor.midi.Midi;
import io.feydor.midi.MidiChannel;
import io.feydor.ui.MidiUi;
import io.feydor.ui.TotalTime;
import io.feydor.util.JsonIo;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;

public class MidiGui implements MidiUi, ChannelListener {
    private JFrame frame;
    private MidiChannel[] channels;
    private JMenuBar[] programMenus;
    private MidiUiEventListener uiEventListener;
    private static final int DROPDOWN_CHAR_WIDTH = 44;

    @Override
    public void block(Midi midi, Future<Void> playbackThread, MidiChannel[] channels, TotalTime remainingTime, MidiUiEventListener uiEventListener) throws Exception {
        this.uiEventListener = uiEventListener;
        if (frame != null)
            return;

        initLookAndFeel();
        frame = new JFrame("CMIDI");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        frame.setSize(300,800);

        JPanel mainPanel = new JPanel(new GridLayout(channels.length + 3, 1));

        // First column, channel controls
        this.channels = channels;
        this.programMenus = new JMenuBar[channels.length];
        for (int i = 0; i < channels.length; ++i) {
            channels[i].addChangeListener(this);

            JPanel rowPanel = new JPanel(new GridLayout(2, 1)); // new FlowLayout(FlowLayout.LEFT)
            rowPanel.add(createMuteButton(channels[i]));
            programMenus[i] = createProgramMenu(channels[i].getCurrentGmProgramName(), i);
            rowPanel.add(programMenus[i]);
            rowPanel.add(new JSlider(JSlider.HORIZONTAL, 0, 100, 50)); // TODO: volume slider
            mainPanel.add(rowPanel);
        }

        // Second column, misc sliders
        mainPanel.add(new JLabel(String.format("Tempo %.2f (ms/tick), %d bpm", midi.msPerTick(), 60_000_000 / midi.getTracks().get(0).getTempo())));
//        mainPanel.add(new JLabel("Song Length"));
//        mainPanel.add(new JSlider(JSlider.HORIZONTAL, 0, 100, 0));
        var checkbox = new JCheckBox("Loop?");
        checkbox.addActionListener(event -> uiEventListener.toggleLoop());
        mainPanel.add(checkbox);

        frame.add(mainPanel);
        frame.setVisible(true);
    }

    private JToggleButton createMuteButton(MidiChannel channel) {
        var button = new JToggleButton(String.valueOf(channel.channel));
        button.addActionListener(e -> {
            if (button.isSelected()) {
                uiEventListener.addChannelVolumeEvent((byte)(channel.channel - 1), (byte)0x00);
            } else {
                uiEventListener.addChannelVolumeEvent((byte)(channel.channel - 1), (byte)127); // TODO: Get initial volume from channel
            }
        });
        return button;
    }

    private JMenuBar createProgramMenu(String initialInstrumentName, int index) {
        Map<String, Object> menuMap = JsonIo.getGmMidiJsonStringMap();
        JMenuBar menuBar = new JMenuBar();
        JMenu rootMenu = new JMenu(initialInstrumentName == null ? " ".repeat(DROPDOWN_CHAR_WIDTH) : initialInstrumentName);
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
                        MidiChannel midiChannel = channels[index];
                        uiEventListener.addProgramChangeEvent((byte) (midiChannel.channel - 1), MidiChannel.gmProgramNameToByte(thisMenuItem.getText()));
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

    /**
     * @param channel One-indexed
     */
    @Override
    public void changed(int channel) {
        MidiChannel changedChannel = channels[channel - 1];
        String newProgramName = changedChannel.getCurrentGmProgramName();
        int remainingFiller = DROPDOWN_CHAR_WIDTH - newProgramName.length();
        programMenus[channel - 1].getMenu(0).setText(newProgramName + " ".repeat(remainingFiller));
    }

    static class SubmenuItem implements Comparable<SubmenuItem> {
        public final String label;
        public final List<String> childItems;
        public final JPopupMenu popupMenu;

        public SubmenuItem(String label, List<String> childItems, JComboBox<Object> parentComboBox) {
            this.label = label;
            this.childItems = childItems;
            this.popupMenu = new JPopupMenu();
            for (String childItem : childItems) {
                popupMenu.add(new JMenuItem(childItem));
            }
        }

        @Override
        public String toString() {
            return label;
        }

        @Override
        public int compareTo(SubmenuItem o) {
            return this.label.compareTo(o.label);
        }
    }

    static class SubmenuItemRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
            Component component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof SubmenuItem submenuItem) {
                setText(submenuItem.label);
            }
            return component;
        }
    }

    private static void initLookAndFeel() {
        try {
            UIManager.setLookAndFeel(new FlatIntelliJLaf());
        } catch (Exception ex) {
            System.err.println(ex.getLocalizedMessage());
            ex.printStackTrace(System.err);
        }
        JFrame.setDefaultLookAndFeelDecorated(true);
    }
}
