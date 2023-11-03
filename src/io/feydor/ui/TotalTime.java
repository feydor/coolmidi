package io.feydor.ui;

public record TotalTime(double ms) {
    public int asSeconds() {
        return (int)Math.round(ms / 1000);
    }

    @Override
    public String toString() {
        double remainingMs = ms;
        int hr = 0, min = 0, sec;

        if (ms / 1000 / 60 / 60 >= 1) {
            hr = (int)ms / 1000 / 60 / 60;
            remainingMs -= hr * 60 * 60 * 1000;
        }

        if (remainingMs / 1000 / 60 > 0) {
            min = (int) remainingMs / 1000 / 60;
            remainingMs -= min * 60 * 1000;
        }

        sec = (int) remainingMs / 1000;

        return String.format("%02d:%02d:%02d", hr, min, sec);
    }
}
