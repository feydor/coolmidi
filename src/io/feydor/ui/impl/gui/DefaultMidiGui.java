package io.feydor.ui.impl.gui;

import io.feydor.midi.Midi;
import io.feydor.ui.IMidiUi;
import io.feydor.ui.MidiController;
import io.feydor.util.FileIo;

import javax.swing.*;
import javax.swing.border.BevelBorder;
import javax.swing.border.EtchedBorder;
import javax.swing.border.SoftBevelBorder;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.File;

public class DefaultMidiGui implements IMidiUi {
    private JFrame frame;
    private MidiController midiController;
    private JFileChooser fileChooser;

    @Override
    public void initialize(Midi midi, MidiController midiController) {
        initLookAndFeel();
        this.midiController = midiController;
        frame = new JFrame("CMIDI");
        frame.setSize(350,580);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setJMenuBar(createFileMenu(false));
        frame.getContentPane().add(new NoMidiPanel(this::newFileHandler));
        frame.setVisible(true);
    }

    public void newFileHandler(File file) {
        System.out.println("Opening: " + file.getAbsolutePath());
        try {
            midiController.loadMidiFile(file);
            if (!midiController.isPlaying()) {
                frame.setJMenuBar(createFileMenu(true));
                // TODO: Enable the file menu again
            } else {
                // TODO: fix
                midiController.replaceCurrentlyPlaying(file);
            }

            midiController.startPlaybackFromBeginning();
            frame.getContentPane().removeAll();
            var newContentPane = new PlayingMidiPanel(midiController.getCurrentlyPlaying(), midiController, this::newFileHandler);
            frame.getContentPane().add(newContentPane);
        } catch (Exception ex) {
            ex.printStackTrace(System.err);
            throw new RuntimeException(ex);
        }
    }

    private JMenuBar createFileMenu(boolean playingAFile) {
        fileChooser = new JFileChooser();
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
            newFileHandler(file);
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

    private static void initLookAndFeel() {
        try {
            // "com.sun.java.swing.plaf.motif.MotifLookAndFeel"
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ex) {
            System.err.println(ex.getLocalizedMessage());
            ex.printStackTrace(System.err);
        }
        JFrame.setDefaultLookAndFeelDecorated(true);
    }
}
