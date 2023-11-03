package io.feydor.ui;

public class Terminal {
    private Terminal() {}

    public static void moveCursorUp(int lines) {
        System.out.print("\033[" + lines + "A");
        System.out.flush();
    }

    public static void carriageReturn() {
        System.out.print("\r");
        System.out.flush();
    }

    public static void clearFromCursor() {
        System.out.print("\033[K");
        System.out.flush();
    }
}
