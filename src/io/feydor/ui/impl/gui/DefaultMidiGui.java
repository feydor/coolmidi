package io.feydor.ui.impl.gui;

import io.feydor.midi.Midi;
import io.feydor.ui.MidiController;
import io.feydor.ui.MidiUi;
import io.feydor.util.FileIo;

import javax.swing.*;
import javax.swing.border.BevelBorder;
import javax.swing.border.EtchedBorder;
import javax.swing.border.SoftBevelBorder;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.ActionEvent;
import java.io.File;
import java.io.IOException;
import java.util.List;

public class DefaultMidiGui implements MidiUi {
    private JFrame frame;
    private MidiController midiController;
    private JFileChooser fileChooser;

    @Override
    public void block(Midi midi, MidiController midiController) throws Exception {
        this.midiController = midiController;
        frame = new JFrame("CMIDI");
        frame.setSize(350,580);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setJMenuBar(createFileMenu(false));
        frame.getContentPane().add(createDraggableArea(), BorderLayout.NORTH);
        frame.setVisible(true);
    }

    private JTextArea createDraggableArea() {
        var area = new JTextArea();
        area.setText("Drag to open a midi file");
        area.setTransferHandler(new TransferHandler(){
            @Override
            public boolean canImport(TransferSupport support) {
                return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
            }

            @Override
            public boolean importData(TransferSupport support) {
                if (!canImport(support)) {
                    return false;
                }

                Transferable t = support.getTransferable();

                try {
                    java.util.List<File> l = (List<File>)t.getTransferData(DataFlavor.javaFileListFlavor);
                    System.out.println("list: " + l);
//                for (File f : l) {
//                    new Doc(f);
//                }
                } catch (UnsupportedFlavorException | IOException e) {
                    return false;
                }

                return true;
            }
        });
        return area;
    }

    private JMenuBar createFileMenu(boolean playingAFile) {
        var openButton = new JButton("Open", FileIo.createImageIcon("images/Open16.gif"));
        openButton.setMaximumSize(new Dimension(100, 100));
        openButton.addActionListener(this::handleOpenFileChooser);
        openButton.setFocusPainted(false);
        openButton.setBorder(new EtchedBorder());

        var buttonPanel = new JMenuBar();
        buttonPanel.setLayout(new BoxLayout(buttonPanel, BoxLayout.X_AXIS));
        buttonPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE - 1, 33));
        buttonPanel.add(openButton);
        buttonPanel.setBorder(new SoftBevelBorder(BevelBorder.RAISED));

        buttonPanel.add(createPlayButton(playingAFile));
        var checkbox = new JCheckBox("Loop?");
        checkbox.setEnabled(playingAFile);
        checkbox.addActionListener(event -> midiController.toggleCurrentMidiLooping());
        checkbox.setFocusPainted(false);
        buttonPanel.add(checkbox);

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

    private JToggleButton createPlayButton(boolean enabled) {
        var playIcon = FileIo.createImageIcon("images/play16.gif");
        var pauseIcon = FileIo.createImageIcon("images/pause16.gif");
        var playButton = new JToggleButton(pauseIcon);
        playButton.setEnabled(enabled);
        playButton.setFocusPainted(false);
        playButton.addActionListener(event -> {
            midiController.togglePlaying();
            if (playButton.isSelected()) {
                playButton.setIcon(playIcon);
//                for (JToggleButton muteButton : muteButtons)
//                    muteButton.setSelected(true);
            } else {
                playButton.setIcon(pauseIcon);
//                for (JToggleButton muteButton : muteButtons)
//                    muteButton.setSelected(false);
            }
        });
        return playButton;
    }
}
