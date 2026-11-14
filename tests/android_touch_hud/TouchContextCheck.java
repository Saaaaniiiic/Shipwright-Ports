package com.dishii.soh;

public final class TouchContextCheck {
    static void check(boolean value, String what) { if (!value) throw new AssertionError(what); }
    static final int DPAD_FLAG = 1 << 22, CUSTOM_FLAG = 1 << 23, RSTICK_FLAG = 1 << 24;
    static final int READY = 1 << 25, NAVI_CALLING = 1 << 26, SKIP_TEXT = 1 << 27;
    // Every state a running game publishes has the READY bit.
    static int st(int context, int flags) { return (context << 18) | READY | flags; }

    static boolean shows(int context, int flags, String key, int mask, int stick, boolean floating, int mode) {
        return TouchContext.shows(st(context, flags), key, mask, stick, floating, mode);
    }
    static boolean btn(int context, int flags, String key, int mask) {
        return shows(context, flags, key, mask, -1, false, 2);
    }

    public static void main(String[] args) {
        final int N = TouchContext.NORMAL, O = TouchContext.OCARINA, C = TouchContext.CUTSCENE,
                D = TouchContext.DIALOG, DC = TouchContext.DIALOG_CHOICE, H = TouchContext.HORSE,
                T = TouchContext.TITLE, F = TouchContext.FILE_SELECT, B = TouchContext.BOOT;
        final int[] everyMask = {0x8000, 0x4000, 1, 2, 4, 8, 0x2000, 0x20, 0x10, 0x1000, 0x10000, 0x800, 0x400, 0x200, 0x100, 0x20000};
        final String[] everyKey = {"a", "b", "cl", "z", "l", "r", "start", "map", "settings", "eye", "pad", "du"};

        // State decoding: no READY bit means the game has not started publishing yet (Boot).
        check(TouchContext.fromState(0) == B, "empty state is boot");
        check(TouchContext.fromState(O << 18) == B, "context without READY is still boot");
        check(TouchContext.fromState(st(N, 0)) == N, "ready normal");
        check(TouchContext.fromState(st(O, 0x3ff00 | 0xff)) == O, "content bits must not leak");
        check(TouchContext.fromState(st(9, 0)) == N && TouchContext.fromState(st(15, 0)) == N, "unknown context falls back to normal");
        check(TouchContext.fromState(st(DC, DPAD_FLAG | CUSTOM_FLAG | RSTICK_FLAG | NAVI_CALLING | SKIP_TEXT)) == DC, "flags never disturb the context");
        check((TouchContext.ALL_BITS & 0x3ffff) == 0, "context bits must not touch low/content bits");
        check(TouchContext.ALL_BITS == ((15 << 18) | DPAD_FLAG | CUSTOM_FLAG | RSTICK_FLAG | READY | NAVI_CALLING | SKIP_TEXT), "ALL_BITS");
        check(TouchContext.isDialog(st(D, 0)) && TouchContext.isDialog(st(DC, 0)) && !TouchContext.isDialog(st(N, 0))
                && !TouchContext.isDialog(D << 18), "isDialog");
        check(TouchContext.skipTextEnabled(st(D, SKIP_TEXT)) && !TouchContext.skipTextEnabled(st(D, 0)), "skip text flag");

        // Boot (app start-up / loading): nothing at all, sticks included.
        for (String key : everyKey) for (int m : everyMask) check(!TouchContext.shows(0, key, m, -1, false, 2), "boot hides " + key + m);
        check(!TouchContext.shows(0, "move", 0, 0, true, 2) && !TouchContext.shows(0, "look", 0, 1, true, 2), "boot hides sticks");
        check(!shows(B, 0, "a", 0x8000, -1, false, 2), "boot context hides A");

        // Normal gameplay keeps every control.
        for (String key : new String[]{"a", "b", "cl", "z", "l", "r", "du", "start", "map", "settings", "eye", "pad"})
            check(btn(N, 0, key, key.equals("a") ? 0x8000 : 0x10), "normal shows " + key);
        check(shows(N, 0, "move", 0, 0, true, 2) && shows(N, 0, "look", 0, 1, false, 2), "normal keeps sticks");
        check(btn(N, 0, "cu", 8) && btn(N, 0, "fairy", 0x20000), "normal always keeps C-up/Navi");

        // Ocarina: A, C and B stay; everything else goes.
        check(btn(O, 0, "a", 0x8000) && btn(O, 0, "b", 0x4000), "ocarina A/B");
        for (int c : new int[]{1, 2, 4, 8, 0x20000}) check(btn(O, 0, "c", c), "ocarina C/Navi " + c);
        check(!btn(O, 0, "start", 0x1000) && !btn(O, 0, "map", 0x10000), "ocarina hides start/map");
        check(!btn(O, 0, "settings", 0) && !btn(O, 0, "eye", 0), "ocarina hides gear/eye");
        check(!btn(O, 0, "z", 0x2000) && !btn(O, 0, "l", 0x20) && !btn(O, 0, "r", 0x10), "ocarina hides Z/L/R by default");
        check(btn(O, CUSTOM_FLAG, "z", 0x2000) && btn(O, CUSTOM_FLAG, "l", 0x20) && btn(O, CUSTOM_FLAG, "r", 0x10),
                "custom ocarina keeps Z/L/R helper notes");
        for (int d : new int[]{0x800, 0x400, 0x200, 0x100}) {
            check(!btn(O, 0, "du", d), "ocarina hides dpad without option " + d);
            check(btn(O, DPAD_FLAG, "du", d), "ocarina shows dpad with option " + d);
        }
        check(!btn(O, 0, "pad", 0) && btn(O, DPAD_FLAG, "pad", 0), "modern dpad group follows the option");
        check(shows(O, 0, "move", 0, 0, true, 2), "floating move stick stays");
        check(!shows(O, 0, "move", 0, 0, false, 2), "fixed move stick hidden");
        check(!shows(O, 0, "look", 0, 1, false, 2), "fixed look stick hidden by default");
        check(shows(O, RSTICK_FLAG, "look", 0, 1, false, 2), "right-stick notes keep GameCube C-stick");
        check(!shows(O, RSTICK_FLAG, "look", 0, 1, false, 3), "right-stick notes only apply to GameCube preset");
        check(!shows(O, RSTICK_FLAG, "look", 0, 1, false, 1), "...nor to N64");

        // Cutscene without text: nothing at all.
        for (String key : everyKey) for (int m : everyMask)
            check(!btn(C, DPAD_FLAG | CUSTOM_FLAG | NAVI_CALLING, key, m), "cutscene hides " + key + m);
        check(!shows(C, 0, "move", 0, 0, true, 2) && !shows(C, 0, "look", 0, 1, true, 2), "cutscene hides sticks");

        // Dialogue: no A/B buttons any more (the whole screen is the button); no sticks either.
        for (String key : everyKey) for (int m : everyMask)
            check(!btn(D, DPAD_FLAG | CUSTOM_FLAG | NAVI_CALLING | SKIP_TEXT, key, m), "dialog hides " + key + m);
        check(!shows(D, 0, "move", 0, 0, true, 2) && !shows(D, 0, "look", 0, 1, true, 2), "dialog hides sticks");
        // Choice boxes keep only the left stick (floating or fixed) to pick the answer.
        for (String key : everyKey) for (int m : everyMask) check(!btn(DC, 0, key, m), "choice hides " + key + m);
        check(shows(DC, 0, "move", 0, 0, true, 2) && shows(DC, 0, "move", 0, 0, false, 2), "choice keeps the left stick");
        check(!shows(DC, 0, "look", 0, 1, true, 2) && !shows(DC, 0, "look", 0, 1, false, 2), "choice hides the right stick");

        // Epona: only A, B, L and the left stick; C-up only while Navi has something to say.
        check(btn(H, 0, "a", 0x8000) && btn(H, 0, "b", 0x4000) && btn(H, 0, "l", 0x20), "horse keeps A/B/L");
        for (int m : new int[]{1, 2, 4, 0x2000, 0x10, 0x1000, 0x10000, 0x800, 0x400, 0x200, 0x100})
            check(!btn(H, NAVI_CALLING | DPAD_FLAG, "x", m), "horse hides mask " + m);
        check(!btn(H, 0, "settings", 0) && !btn(H, 0, "eye", 0) && !btn(H, 0, "pad", 0), "horse hides gear/eye/pad");
        check(!btn(H, 0, "cu", 8) && !btn(H, 0, "fairy", 0x20000), "horse hides C-up while Navi is quiet");
        check(btn(H, NAVI_CALLING, "cu", 8) && btn(H, NAVI_CALLING, "fairy", 0x20000), "horse shows C-up when Navi calls");
        check(shows(H, 0, "move", 0, 0, false, 2) && shows(H, 0, "move", 0, 0, true, 2), "horse keeps the steering stick");
        check(!shows(H, 0, "look", 0, 1, false, 2) && !shows(H, 0, "look", 0, 1, true, 2), "horse hides the right stick");

        // Title: nothing at all (the whole screen is a tap target).
        for (String key : everyKey) for (int m : everyMask) check(!btn(T, DPAD_FLAG, key, m), "title hides " + key + m);
        check(!shows(T, 0, "move", 0, 0, true, 2) && !shows(T, 0, "look", 0, 1, true, 2), "title hides sticks");

        // File select: A, B and the left stick only.
        check(btn(F, 0, "a", 0x8000) && btn(F, 0, "b", 0x4000), "files show A/B");
        for (int m : new int[]{1, 2, 4, 8, 0x2000, 0x20, 0x10, 0x1000, 0x10000, 0x20000, 0x800, 0x400, 0x200, 0x100})
            check(!btn(F, DPAD_FLAG | CUSTOM_FLAG, "x", m), "files hide mask " + m);
        check(!btn(F, 0, "settings", 0) && !btn(F, 0, "eye", 0) && !btn(F, 0, "pad", 0) && !btn(F, 0, "start", 0x1000),
                "files hide gear/eye/pad/start");
        check(shows(F, 0, "move", 0, 0, true, 2) && shows(F, 0, "move", 0, 0, false, 2), "files keep the left stick");
        check(!shows(F, 0, "look", 0, 1, true, 2) && !shows(F, 0, "look", 0, 1, false, 2), "files hide the right stick");

        // D-pad stick navigation only exists on the file select (kept for rebinding the D-pad there).
        check(TouchContext.navigatesFileSelect(st(F, 0), 0x800, -1), "file select dpad navigates");
        check(!TouchContext.navigatesFileSelect(st(N, 0), 0x800, -1), "normal dpad is a button");
        check(!TouchContext.navigatesFileSelect(st(F, 0), 0x8000, -1), "A is never navigation");
        check(!TouchContext.navigatesFileSelect(st(F, 0), 0x800, 0), "sticks are never navigation");

        System.out.println("Touch context policy checks passed");
    }
}
