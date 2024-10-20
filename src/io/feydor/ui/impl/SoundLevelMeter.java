package io.feydor.ui.impl;

import javax.swing.*;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import java.awt.*;

public class SoundLevelMeter extends JComponent {
    private int soundLevel = 0;
    private Color yellow = Color.YELLOW;
    private Color green = Color.GREEN;
    private Color red = Color.RED;

    public SoundLevelMeter() {
        setPreferredSize(new Dimension(100, 20));
//        addChangeListener(new ChangeListener() {
//            @Override
//            public void stateChanged(ChangeEvent e) {
//                repaint();
//            }
//        });
    }

    public void setSoundLevel(int soundLevel) {
        this.soundLevel = Math.max(0, Math.min(soundLevel, 100));
        repaint();
//        fireStateChanged();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);

        int width = getWidth();
        int height = getHeight();

        g.setColor(yellow);

        g.fillRect(0, 0, width, height);

        int greenWidth = (int) (width * (soundLevel / 100.0));
        g.setColor(green);
        g.fillRect(0, 0, greenWidth, height);

        if (soundLevel > 50) {
            int redWidth = (int) (width * ((soundLevel - 50) / 100.0));
            g.setColor(red);
            g.fillRect(greenWidth, 0, redWidth, height);

            // Create a gradient between green and red
//            GradientPaint gradient = new GradientPaint(greenWidth, 0, green, greenWidth + redWidth, 0, red);
//            g.setPaint(gradient);
//            g.
            g.fillRect(greenWidth, 0, redWidth, height);
        }
    }
}