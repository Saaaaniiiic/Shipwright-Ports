#include "soh/soh/Enhancements/controls/TouchHudContext.h"
#include <cassert>
int main() {
    using namespace TouchHud;
    const ContextOptions on;
    ContextOptions off; off.cutscenes = off.ocarina = off.horse = off.title = off.dialogs = false;

    // Bit packing never collides with the content bits (8..17) or the low flags.
    for (int c = 0; c <= static_cast<int>(Context::DialogChoice); ++c) {
        const Context ctx = static_cast<Context>(c);
        const int bits = ContextBits(ctx);
        assert((bits & 0x3ffff) == 0);
        assert(ContextFromState(bits) == ctx);
        assert(ContextFromState(bits | 0x3ffff) == ctx);
    }
    assert(ContextFromState(9 << ContextShift) == Context::Normal);   // unknown value
    assert(ContextFromState(15 << ContextShift) == Context::Normal);
    assert(ContextFromState(0) == Context::Normal);
    // Flags sit above the context field and never disturb it.
    assert(OcarinaFlagBits(false, false, false) == 0);
    assert(OcarinaFlagBits(true, false, false) == OcarinaFlagDpad);
    assert(OcarinaFlagBits(true, true, true) == (OcarinaFlagDpad | OcarinaFlagCustom | OcarinaFlagRightStick));
    const int flags = OcarinaFlagDpad | OcarinaFlagCustom | OcarinaFlagRightStick | StateReady | StateNaviCalling | StateSkipText;
    assert((ContextBits(Context::DialogChoice) & flags) == 0);
    assert(ContextFromState(ContextBits(Context::Horse) | flags) == Context::Horse);
    assert((ContextAllBits & 0x3ffff) == 0);                        // content bits 8..17 stay clear
    assert(ContextAllBits == ((15 << ContextShift) | flags));
    assert((StateReady & OcarinaFlagRightStick) == 0 && (StateNaviCalling & StateSkipText) == 0);

    // A text box is active from the first typed character until it closes.
    assert(!DialogActive(0x00, 0x00));                // no message
    assert(DialogActive(0x01, 0x00));                 // starting
    assert(DialogActive(0x04, 0x00));                 // handing over to the next box
    assert(DialogActive(0x06, 0x00));                 // typing
    assert(DialogActive(0x07, 0x00));                 // awaiting input
    assert(DialogActive(0x08, 0x00));                 // delayed break
    assert(DialogActive(0x34, 0x30));                 // awaiting next box
    assert(DialogActive(0x35, 0x00));                 // done, default end
    assert(DialogActive(0x35, 0x10));                 // 2 choices
    assert(DialogActive(0x35, 0x20));                 // 3 choices
    assert(!DialogActive(0x35, 0x40));                // persistent
    assert(!DialogActive(0x35, 0x50));                // event
    assert(!DialogActive(0x35, 0x60));                // auto fade
    assert(!DialogActive(0x36, 0x00));                // closing
    assert(!DialogActive(0x09, 0x00));                // ocarina message modes
    assert(!DialogActive(0x0C, 0x00));
    assert(DialogIsChoice(0x10) && DialogIsChoice(0x20) && !DialogIsChoice(0x00) && !DialogIsChoice(0x30));

    ContextInputs in;
    assert(ClassifyContext(in, on) == Context::Boot);            // no game yet: nothing on screen
    assert(ClassifyContext(in, off) == Context::Normal);         // dynamic title off: old behaviour
    in.inGame = true;
    assert(ClassifyContext(in, on) == Context::Normal);
    in.horse = true;
    assert(ClassifyContext(in, on) == Context::Horse);
    assert(ClassifyContext(in, off) == Context::Normal);
    in.cutscene = true;                                           // cutscene beats horse
    assert(ClassifyContext(in, on) == Context::Cutscene);
    in.dialogActive = true;                                       // dialogue beats cutscene
    assert(ClassifyContext(in, on) == Context::Dialog);
    in.dialogChoice = true;
    assert(ClassifyContext(in, on) == Context::DialogChoice);
    in.paused = true;                                             // the pause menu is not a dialogue
    assert(ClassifyContext(in, on) == Context::Cutscene);
    in.paused = false;
    ContextOptions noDialogs = on; noDialogs.dialogs = false;
    assert(ClassifyContext(in, noDialogs) == Context::Cutscene);
    in.dialogActive = in.dialogChoice = false;
    in.ocarina = true;                                            // ocarina beats cutscene
    assert(ClassifyContext(in, on) == Context::Ocarina);
    ContextOptions noOcarina = on; noOcarina.ocarina = false;
    assert(ClassifyContext(in, noOcarina) == Context::Cutscene);
    ContextOptions noCutscenes = on; noCutscenes.cutscenes = false;
    in.ocarina = false;
    assert(ClassifyContext(in, noCutscenes) == Context::Horse);

    // Ordinary NPC talk is a dialogue too, on foot or on Epona.
    ContextInputs talk; talk.inGame = true; talk.dialogActive = true;
    assert(ClassifyContext(talk, on) == Context::Dialog);
    talk.horse = true;
    assert(ClassifyContext(talk, on) == Context::Dialog);
    assert(ClassifyContext(talk, noDialogs) == Context::Horse);

    // Credits count as a cutscene.
    ContextInputs credits; credits.inGame = true; credits.gameMode = GameModeCredits;
    assert(ClassifyContext(credits, on) == Context::Cutscene);
    assert(ClassifyContext(credits, noCutscenes) == Context::Normal);

    // Title / file select work with or without a PlayState and win over everything.
    ContextInputs title; title.gameMode = GameModeTitle;
    assert(ClassifyContext(title, on) == Context::Title);
    title.inGame = true; title.cutscene = true; title.ocarina = true; title.dialogActive = true;
    assert(ClassifyContext(title, on) == Context::Title);
    assert(ClassifyContext(title, off) == Context::Normal);       // every option off
    ContextInputs files; files.gameMode = GameModeFileSelect;
    assert(ClassifyContext(files, on) == Context::FileSelect);
    assert(ClassifyContext(files, off) == Context::Normal);

    // Debounce: a context must hold for 3 updates; a single-frame blip is ignored.
    // The tracker starts in Boot, so the buttons never flash before the game screen is known.
    ContextTracker tracker;
    assert(tracker.Update(Context::Boot) == Context::Boot);
    assert(tracker.Update(Context::Normal) == Context::Boot);
    assert(tracker.Update(Context::Normal) == Context::Boot);
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
    assert(tracker.Update(Context::Boot) == Context::Boot);
    assert(tracker.Update(Context::Horse, 1) == Context::Horse);  // hold = 1 is immediate
}
