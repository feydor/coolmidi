package io.feydor.ui.impl;

import com.formdev.flatlaf.FlatDarculaLaf;
import com.formdev.flatlaf.FlatIntelliJLaf;
import com.formdev.flatlaf.FlatLightLaf;
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
    private JMenuBar[] instrumentMenus;
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

        JPanel mainPanel = new JPanel(new GridLayout(channels.length + 6, 1));

        // First column, channel controls
        this.channels = channels;
        this.instrumentMenus = new JMenuBar[channels.length];
        for (int i = 0; i < channels.length; ++i) {
            var channel = channels[i];
            channel.addChangeListener(this);

            JPanel rowPanel = new JPanel(new GridLayout(2, 1)); // new FlowLayout(FlowLayout.LEFT)
            rowPanel.add(getMuteButton(channel));

            instrumentMenus[i] = getMenu(channel.getCurrentGmProgramName(), i);
//            JPanel instrumentMenuFixedPanel = new JPanel();
//            instrumentMenuFixedPanel.setSize(new Dimension(100, 50));
//            instrumentMenuFixedPanel.setPreferredSize(new Dimension(100, 50));
//            instrumentMenuFixedPanel.add(instrumentMenus[i]);
            rowPanel.add(instrumentMenus[i]);

            rowPanel.add(new JSlider(JSlider.HORIZONTAL, 0, 100, 50)); // TODO: volume slider
            mainPanel.add(rowPanel);
        }

        // Second column, misc sliders
        mainPanel.add(new JLabel("Tempo"));
        mainPanel.add(new JSlider(JSlider.HORIZONTAL, 0, 100, 50));
        mainPanel.add(new JLabel("Events"));
        mainPanel.add(new JSlider(JSlider.HORIZONTAL, 0, 100, 50));
        mainPanel.add(new JLabel("Song Length"));
        mainPanel.add(new JSlider(JSlider.HORIZONTAL, 0, 100, 0));

        frame.add(mainPanel);
        frame.setVisible(true);
    }

    private JToggleButton getMuteButton(MidiChannel channel) {
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

    private JMenuBar getMenu(String initialInstrumentName, int index) {
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
                        String oldInstrumentName = rootMenu.getText();
                        rootMenu.setText(thisMenuItem.getText());

                        System.out.println("Current instruments: " + Arrays.stream(channels).map(MidiChannel::getCurrentGmProgramName).toList());
                        System.out.println("Current Instrument in UI: " + oldInstrumentName);
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

    private JComboBox<Object> getComboBoxFromJson() {
        Map<String, Object> menuMap = JsonIo.getGmMidiJsonStringMap();
        JComboBox<Object> comboBox = new JComboBox<>();

        // Populate nodes based on JSON file
        List<Object> topLevelItems = new ArrayList<>();
        for (var entrySet : menuMap.entrySet()) {
            var groupName = entrySet.getKey();
            List<String> childItems = new ArrayList<>();
            if (entrySet.getValue() instanceof List instrNames) {
                for (Object name : instrNames) {
                    childItems.add((String) name);
                }
                topLevelItems.add(new SubmenuItem(groupName, childItems, comboBox));
            } else {
                throw new RuntimeException("The JSON value was not a List!: " + entrySet.getValue() + ", but was " + entrySet.getValue().getClass());
            }
        }

        comboBox.setModel(new DefaultComboBoxModel<>(topLevelItems.toArray(new Object[]{})));
//        comboBox.setRenderer(new DefaultListCellRenderer());
        comboBox.setRenderer(new SubmenuItemRenderer());
//        comboBox.addActionListener(e -> {
//            if (comboBox.getSelectedItem() instanceof SubmenuItem submenuItem) {
//                System.out.println("Selected: " + submenuItem.label);
//                submenuItem.popupMenu.show(comboBox, 0, comboBox.getHeight());
//            }
//        });

        comboBox.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                if (e.getSource() instanceof JComboBox) {
                    if (comboBox.getSelectedItem() instanceof SubmenuItem submenuItem) {
                        submenuItem.popupMenu.show(comboBox, 0, comboBox.getHeight());
                    }
                }
            }
        });

        return comboBox;
    }

    @Override
    public void changed(int channel) {
        MidiChannel changedChannel = channels[channel - 1];
        String newProgramName = changedChannel.getCurrentGmProgramName();

        System.out.println("changed channel =" + channel + " old " + instrumentMenus[channel - 1].getMenu(0).getText() + " VS new " + newProgramName);
        int remainingFiller = DROPDOWN_CHAR_WIDTH - newProgramName.length();
        instrumentMenus[channel - 1].getMenu(0).setText(newProgramName + " ".repeat(remainingFiller));
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
                // Customize appearance for submenus (e.g., different icon, background color)
//                setIcon(new ImageIcon("submenu_icon.png"));
                setText(submenuItem.label);
            }
            return component;
        }
    }

    private static void initLookAndFeel() {
        try {
            UIManager.setLookAndFeel(new FlatIntelliJLaf());
        } catch (Exception ex) {
            System.out.println(ex.getLocalizedMessage());
            ex.printStackTrace(System.err);
        }
        JFrame.setDefaultLookAndFeelDecorated(true);
    }
}
