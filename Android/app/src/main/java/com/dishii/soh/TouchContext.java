package com.dishii.soh;

/**
 * Which integrated touch controls are relevant in the current game context.
 * The native side (TouchHudContext.h) publishes the context and a few ocarina
 * option flags in the high bits of the touch state; this class only decides.
 * Controls are matched by their game-action mask, not their key, so a rebound
 * button follows what it actually does.
 */
final class TouchContext {
    static final int NORMAL = 0, OCARINA = 1, CUTSCENE = 2, CUTSCENE_DIALOG = 3, HORSE = 4, TITLE = 5, FILE_SELECT = 6;
    private static final int SHIFT = 18, MASK = 7;
    private static final int FLAG_DPAD = 1 << 21, FLAG_CUSTOM = 1 << 22, FLAG_RIGHT_STICK = 1 << 23;
    /** Every state bit that belongs to the context (field plus ocarina flags). */
    static final int ALL_BITS = (MASK << SHIFT) | FLAG_DPAD | FLAG_CUSTOM | FLAG_RIGHT_STICK;

    static final int A = 0x8000, B = 0x4000, Z = 0x2000, START = 0x1000, L = 0x20, R = 0x10;
    static final int C_BUTTONS = 1 | 2 | 4 | 8, NAVI = 0x20000;
    static final int DPAD = 0x800 | 0x400 | 0x200 | 0x100;

    private TouchContext() {}

    static int fromState(int state) {
        int value = (state >> SHIFT) & MASK;
        return value <= FILE_SELECT ? value : NORMAL;
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
                return false;
            case CUTSCENE_DIALOG:
                return stick < 0 && (mask & (A | B)) != 0;
            case HORSE:
                // Epona: shield (R) and L do nothing, everything else is still in play.
                return stick >= 0 || (mask & (L | R)) == 0;
            case TITLE:
                // The whole screen is a tap target; only the menu gear stays on screen.
                return gear;
            case FILE_SELECT:
                if (stick >= 0) return floating;
                if (gear || eye || dpad) return true;
                return (mask & (A | B | START)) != 0;
            default:
                return true;
        }
    }
}
