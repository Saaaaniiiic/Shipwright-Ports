#include "soh/soh/Enhancements/controls/TouchHudContext.h"
#include <cassert>
int main() {
    using namespace TouchHud;
    const ContextOptions on;
    ContextOptions off; off.cutscenes = off.ocarina = off.horse = off.title = false;

    // Bit packing never collides with the content bits (8..17) or the low flags.
    for (int c = 0; c <= static_cast<int>(Context::FileSelect); ++c) {
        const Context ctx = static_cast<Context>(c);
        const int bits = ContextBits(ctx);
        assert((bits & 0x3ffff) == 0);
        assert(ContextFromState(bits) == ctx);
        assert(ContextFromState(bits | 0x3ffff) == ctx);
    }
    assert(ContextFromState(7 << ContextShift) == Context::Normal);   // unknown value
    assert(ContextFromState(0) == Context::Normal);
    // Option flags sit above the context field and never disturb it.
    assert(OcarinaFlagBits(false, false, false) == 0);
    assert(OcarinaFlagBits(true, false, false) == OcarinaFlagDpad);
    assert(OcarinaFlagBits(true, true, true) == (OcarinaFlagDpad | OcarinaFlagCustom | OcarinaFlagRightStick));
    assert((ContextBits(Context::FileSelect) & (OcarinaFlagDpad | OcarinaFlagCustom | OcarinaFlagRightStick)) == 0);
    assert(ContextFromState(ContextBits(Context::Horse) | OcarinaFlagBits(true, true, true)) == Context::Horse);
    assert((ContextAllBits & 0x3ffff) == 0);                        // content bits 8..17 stay clear

    // Dialog needs the player only once it has finished typing and is waiting.
    assert(!DialogNeedsInput(0x00, 0x00));            // no message
    assert(!DialogNeedsInput(0x06, 0x00));            // still typing
    assert(DialogNeedsInput(0x07, 0x00));             // awaiting input
    assert(DialogNeedsInput(0x34, 0x30));             // awaiting next box
    assert(DialogNeedsInput(0x35, 0x00));             // done, default end
    assert(DialogNeedsInput(0x35, 0x10));             // 2 choices
    assert(DialogNeedsInput(0x35, 0x20));             // 3 choices
    assert(!DialogNeedsInput(0x35, 0x40));            // persistent
    assert(!DialogNeedsInput(0x35, 0x50));            // event
    assert(!DialogNeedsInput(0x35, 0x60));            // auto fade
    assert(!DialogNeedsInput(0x36, 0x00));            // closing
    assert(!DialogNeedsInput(0x0C, 0x00));            // ocarina message modes

    ContextInputs in;
    assert(ClassifyContext(in, on) == Context::Normal);          // no game, no mode
    in.inGame = true;
    assert(ClassifyContext(in, on) == Context::Normal);
    in.horse = true;
    assert(ClassifyContext(in, on) == Context::Horse);
    assert(ClassifyContext(in, off) == Context::Normal);
    in.cutscene = true;                                           // cutscene beats horse
    assert(ClassifyContext(in, on) == Context::Cutscene);
    in.dialogNeedsInput = true;
    assert(ClassifyContext(in, on) == Context::CutsceneDialog);
    in.dialogNeedsInput = false;
    in.ocarina = true;                                            // ocarina beats cutscene
    assert(ClassifyContext(in, on) == Context::Ocarina);
    ContextOptions noOcarina = on; noOcarina.ocarina = false;
    assert(ClassifyContext(in, noOcarina) == Context::Cutscene);
    ContextOptions noCutscenes = on; noCutscenes.cutscenes = false;
    in.ocarina = false;
    assert(ClassifyContext(in, noCutscenes) == Context::Horse);

    // Dialog outside a cutscene never changes anything (normal NPC talk).
    ContextInputs talk; talk.inGame = true; talk.dialogNeedsInput = true;
    assert(ClassifyContext(talk, on) == Context::Normal);

    // Credits count as a cutscene.
    ContextInputs credits; credits.inGame = true; credits.gameMode = GameModeCredits;
    assert(ClassifyContext(credits, on) == Context::Cutscene);
    assert(ClassifyContext(credits, noCutscenes) == Context::Normal);

    // Title / file select work with or without a PlayState and win over everything.
    ContextInputs title; title.gameMode = GameModeTitle;
    assert(ClassifyContext(title, on) == Context::Title);
    title.inGame = true; title.cutscene = true; title.ocarina = true;
    assert(ClassifyContext(title, on) == Context::Title);
    assert(ClassifyContext(title, off) == Context::Normal);       // every option off
    ContextInputs files; files.gameMode = GameModeFileSelect;
    assert(ClassifyContext(files, on) == Context::FileSelect);
    assert(ClassifyContext(files, off) == Context::Normal);

    // Debounce: a context must hold for 3 updates; a single-frame blip is ignored.
    ContextTracker tracker;
    assert(tracker.Update(Context::Normal) == Context::Normal);
    assert(tracker.Update(Context::Cutscene) == Context::Normal);
    assert(tracker.Update(Context::Normal) == Context::Normal);   // blip ends, no change
    assert(tracker.Update(Context::Cutscene) == Context::Normal);
    assert(tracker.Update(Context::Cutscene) == Context::Normal);
    assert(tracker.Update(Context::Cutscene) == Context::Cutscene); // third consecutive
    assert(tracker.Update(Context::Cutscene) == Context::Cutscene);
    assert(tracker.Update(Context::Normal) == Context::Cutscene);
    assert(tracker.Update(Context::Normal) == Context::Cutscene);
    assert(tracker.Update(Context::Normal) == Context::Normal);   // buttons return
    // Switching straight between two non-normal contexts also needs the hold.
    assert(tracker.Update(Context::Ocarina) == Context::Normal);
    assert(tracker.Update(Context::Cutscene) == Context::Normal); // candidate restarts
    assert(tracker.Update(Context::Cutscene) == Context::Normal);
    assert(tracker.Update(Context::Cutscene) == Context::Cutscene);
    tracker.Reset();
    assert(tracker.Update(Context::Normal) == Context::Normal);
    assert(tracker.Update(Context::Horse, 1) == Context::Horse);  // hold = 1 is immediate
}
