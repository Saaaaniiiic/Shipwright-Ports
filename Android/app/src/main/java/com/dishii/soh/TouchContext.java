package com.dishii.soh;

/**
 * Which integrated touch controls are relevant in the current game context.
 * The native side (TouchHudContext.h) publishes the context and a few ocarina
 * option flags in the high bits of the touch state; this class only decides.
 * Controls are matched by their game-action mask, not their key, so a rebound
 * button follows what it actually does.
 */
final class TouchContext {
    static final int NORMAL = 0, OCARINA = 1, CUTSCENE = 2, DIALOG = 3, HORSE = 4, TITLE = 5, FILE_SELECT = 6,
            BOOT = 7, DIALOG_CHOICE = 8;
    private static final int SHIFT = 18, MASK = 15;
    private static final int FLAG_DPAD = 1 << 22, FLAG_CUSTOM = 1 << 23, FLAG_RIGHT_STICK = 1 << 24;
    /** Set by the native hook from its first run; before that nothing is on screen yet. */
    private static final int READY = 1 << 25, NAVI_CALLING = 1 << 26, SKIP_TEXT = 1 << 27;
    /** Every state bit that belongs to the context (field plus flags). */
    static final int ALL_BITS = (MASK << SHIFT) | FLAG_DPAD | FLAG_CUSTOM | FLAG_RIGHT_STICK | READY | NAVI_CALLING | SKIP_TEXT;

    static final int A = 0x8000, B = 0x4000, Z = 0x2000, START = 0x1000, L = 0x20, R = 0x10;
    static final int C_BUTTONS = 1 | 2 | 4 | 8, C_UP = 8, NAVI = 0x20000;
    static final int DPAD = 0x800 | 0x400 | 0x200 | 0x100;

    private TouchContext() {}

    static int fromState(int state) {
        if ((state & READY) == 0) return BOOT;
        int value = (state >> SHIFT) & MASK;
        return value <= DIALOG_CHOICE ? value : NORMAL;
    }

    /** A text box is waiting for the player: the whole screen is the button. */
    static boolean isDialog(int state) {
        int context = fromState(state);
        return context == DIALOG || context == DIALOG_CHOICE;
    }

    /** "Skip Text" is on, so holding the screen fast-forwards dialogue (B held). */
    static boolean skipTextEnabled(int state) {
        return (state & SKIP_TEXT) != 0;
    }

    static boolean isDpad(String key, int mask, int stick) {
        return key.equals("pad") || (stick < 0 && (mask & DPAD) != 0);
    }

    /** D-pad buttons double as menu navigation (stick deflection) on the file select. */
    static boolean navigatesFileSelect(int state, int mask, int stick) {
        return fromState(state) == FILE_SELECT && stick < 0 && (mask & DPAD) != 0;
    }

    /**
     * @param floating the stick is floating, so nothing is drawn until it is touched
     * @param mode     integrated preset: 1 N64, 2 GameCube, 3 Modern
     */
    static boolean shows(int state, String key, int mask, int stick, boolean floating, int mode) {
        final boolean gear = key.equals("settings"), eye = key.equals("eye");
        final boolean dpad = isDpad(key, mask, stick);
        switch (fromState(state)) {
            case OCARINA: {
                // Notes (A + C), B to leave, D-pad notes and the L/R/Z helpers only when those options are on.
                // A stick that draws nothing until touched stays available for pitch bend.
                if (stick >= 0) return floating || (stick == 1 && mode == 2 && (state & FLAG_RIGHT_STICK) != 0);
                if (gear || eye) return false;
                if (dpad) return (state & FLAG_DPAD) != 0;
                int keep = A | B | C_BUTTONS | NAVI;
                if ((state & FLAG_CUSTOM) != 0) keep |= Z | L | R;
                return (mask & keep) != 0;
            }
            case CUTSCENE:
            case BOOT:
            case TITLE:
                // Cutscenes and start-up show nothing; the title screen is one big tap target.
                return false;
            case DIALOG:
                // Dialogue is driven by tapping the screen, not by A/B buttons.
                return false;
            case DIALOG_CHOICE:
                // Choices also need the left stick to pick an answer.
                return stick == 0;
            case HORSE:
                // Epona: A, B, L and the left stick, plus C-up only while Navi has something to say.
                if (stick >= 0) return stick == 0;
                if (mask == C_UP || (mask & NAVI) != 0) return (state & NAVI_CALLING) != 0;
                return (mask & (A | B | L)) != 0;
            case FILE_SELECT:
                // A, B and the left stick (floating or fixed) are all the menus need.
                if (stick >= 0) return stick == 0;
                return (mask & (A | B)) != 0;
            default:
                return true;
        }
    }
}
