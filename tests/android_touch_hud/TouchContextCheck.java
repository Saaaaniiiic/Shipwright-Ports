package com.dishii.soh;

public final class TouchContextCheck {
    static void check(boolean value, String what) { if (!value) throw new AssertionError(what); }
    static int st(int context, int flags) { return (context << 18) | flags; }
    static final int DPAD_FLAG = 1 << 21, CUSTOM_FLAG = 1 << 22, RSTICK_FLAG = 1 << 23;

    static boolean shows(int context, int flags, String key, int mask, int stick, boolean floating, int mode) {
        return TouchContext.shows(st(context, flags), key, mask, stick, floating, mode);
    }
    static boolean btn(int context, int flags, String key, int mask) {
        return shows(context, flags, key, mask, -1, false, 2);
    }

    public static void main(String[] args) {
        final int N = TouchContext.NORMAL, O = TouchContext.OCARINA, C = TouchContext.CUTSCENE,
                CD = TouchContext.CUTSCENE_DIALOG, H = TouchContext.HORSE, T = TouchContext.TITLE, F = TouchContext.FILE_SELECT;
        // State decoding ignores content bits (8..17) and unknown values.
        check(TouchContext.fromState(0) == N, "empty state");
        check(TouchContext.fromState((O << 18) | 0x3ff00 | 0xff) == O, "content bits must not leak");
        check(TouchContext.fromState(7 << 18) == N, "unknown context falls back to normal");
        check((TouchContext.ALL_BITS & 0x3ffff) == 0, "context bits must not touch low/content bits");

        // Normal gameplay keeps every control.
        for (String key : new String[]{"a", "b", "cl", "z", "l", "r", "du", "start", "map", "settings", "eye", "pad"})
            check(btn(N, 0, key, key.equals("a") ? 0x8000 : 0x10), "normal shows " + key);
        check(shows(N, 0, "move", 0, 0, true, 2) && shows(N, 0, "look", 0, 1, false, 2), "normal keeps sticks");

        // Ocarina: A, C and B stay; everything else goes.
        check(btn(O, 0, "a", 0x8000) && btn(O, 0, "b", 0x4000), "ocarina A/B");
        for (int c : new int[]{1, 2, 4, 8, 0x20000}) check(btn(O, 0, "c", c), "ocarina C/Navi " + c);
        check(!btn(O, 0, "start", 0x1000) && !btn(O, 0, "map", 0x10000), "ocarina hides start/map");
        check(!btn(O, 0, "settings", 0) && !btn(O, 0, "eye", 0), "ocarina hides gear/eye");
        check(!btn(O, 0, "z", 0x2000) && !btn(O, 0, "l", 0x20) && !btn(O, 0, "r", 0x10), "ocarina hides Z/L/R by default");
        check(btn(O, CUSTOM_FLAG, "z", 0x2000) && btn(O, CUSTOM_FLAG, "l", 0x20) && btn(O, CUSTOM_FLAG, "r", 0x10),
                "custom ocarina keeps Z/L/R helper notes");
        // D-pad only when "Dpad Ocarina Playback" is on.
        for (int d : new int[]{0x800, 0x400, 0x200, 0x100}) {
            check(!btn(O, 0, "du", d), "ocarina hides dpad without option " + d);
            check(btn(O, DPAD_FLAG, "du", d), "ocarina shows dpad with option " + d);
        }
        check(!btn(O, 0, "pad", 0) && btn(O, DPAD_FLAG, "pad", 0), "modern dpad group follows the option");
        // Sticks: invisible floating sticks stay (pitch bend); fixed ones go unless used for notes.
        check(shows(O, 0, "move", 0, 0, true, 2), "floating move stick stays");
        check(!shows(O, 0, "move", 0, 0, false, 2), "fixed move stick hidden");
        check(!shows(O, 0, "look", 0, 1, false, 2), "fixed look stick hidden by default");
        check(shows(O, RSTICK_FLAG, "look", 0, 1, false, 2), "right-stick notes keep GameCube C-stick");
        check(!shows(O, RSTICK_FLAG, "look", 0, 1, false, 3), "right-stick notes only apply to GameCube preset");
        check(!shows(O, RSTICK_FLAG, "look", 0, 1, false, 1), "...nor to N64");

        // Cutscene without text: nothing at all.
        for (String key : new String[]{"a", "b", "cl", "start", "settings", "eye", "pad", "du"})
            check(!btn(C, DPAD_FLAG | CUSTOM_FLAG, key, key.equals("a") ? 0x8000 : key.equals("b") ? 0x4000 : 0x1000), "cutscene hides " + key);
        check(!shows(C, 0, "move", 0, 0, true, 2) && !shows(C, 0, "look", 0, 1, true, 2), "cutscene hides sticks");

        // Cutscene with a waiting text box: only A and B.
        check(btn(CD, 0, "a", 0x8000) && btn(CD, 0, "b", 0x4000), "dialog shows A/B");
        for (int m : new int[]{1, 2, 4, 8, 0x2000, 0x20, 0x10, 0x1000, 0x10000, 0x800, 0x20000})
            check(!btn(CD, DPAD_FLAG, "x", m), "dialog hides mask " + m);
        check(!btn(CD, 0, "settings", 0) && !btn(CD, 0, "eye", 0) && !btn(CD, 0, "pad", 0), "dialog hides gear/eye/pad");
        check(!shows(CD, 0, "move", 0, 0, true, 2), "dialog hides sticks");

        // Epona: only the shield (R) and L go.
        check(!btn(H, 0, "r", 0x10) && !btn(H, 0, "l", 0x20), "horse hides R/L");
        for (int m : new int[]{0x8000, 0x4000, 1, 2, 4, 8, 0x2000, 0x1000, 0x10000, 0x20000, 0x800})
            check(btn(H, 0, "x", m), "horse keeps mask " + m);
        check(btn(H, 0, "settings", 0) && btn(H, 0, "eye", 0) && btn(H, 0, "pad", 0), "horse keeps gear/eye/pad");
        check(shows(H, 0, "move", 0, 0, false, 2), "horse keeps steering stick");

        // Title: only the gear is drawn; the rest of the screen is a tap target.
        check(btn(T, 0, "settings", 0), "title shows gear");
        for (String key : new String[]{"a", "b", "start", "eye", "pad", "du"})
            check(!btn(T, DPAD_FLAG, key, key.equals("a") ? 0x8000 : key.equals("b") ? 0x4000 : 0x1000), "title hides " + key);
        check(!shows(T, 0, "move", 0, 0, true, 2), "title hides sticks");

        // File select: A, B, Start, D-pad (as navigation), gear and eye; invisible floating sticks.
        check(btn(F, 0, "a", 0x8000) && btn(F, 0, "b", 0x4000) && btn(F, 0, "start", 0x1000), "files show A/B/Start");
        for (int d : new int[]{0x800, 0x400, 0x200, 0x100}) check(btn(F, 0, "du", d), "files show dpad " + d);
        check(btn(F, 0, "pad", 0) && btn(F, 0, "settings", 0) && btn(F, 0, "eye", 0), "files show pad/gear/eye");
        for (int m : new int[]{1, 2, 4, 8, 0x2000, 0x20, 0x10, 0x10000, 0x20000})
            check(!btn(F, 0, "x", m), "files hide mask " + m);
        check(shows(F, 0, "move", 0, 0, true, 2) && !shows(F, 0, "move", 0, 0, false, 2), "files: floating sticks only");

        // D-pad becomes stick navigation only on the file select.
        check(TouchContext.navigatesFileSelect(st(F, 0), 0x800, -1), "file select dpad navigates");
        check(!TouchContext.navigatesFileSelect(st(N, 0), 0x800, -1), "normal dpad is a button");
        check(!TouchContext.navigatesFileSelect(st(F, 0), 0x8000, -1), "A is never navigation");
        check(!TouchContext.navigatesFileSelect(st(F, 0), 0x800, 0), "sticks are never navigation");

        System.out.println("Touch context policy checks passed");
    }
}
