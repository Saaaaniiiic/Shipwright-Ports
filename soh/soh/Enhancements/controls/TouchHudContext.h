#pragma once
#include <cstdint>

namespace TouchHud {
// What the player is doing right now, as far as the touch overlay cares.
// Published to the Android/iOS view in state bits 18..21 (see TouchHud.cpp).
enum class Context : int {
    Normal = 0,
    Ocarina,         // playing the ocarina
    Cutscene,        // scripted cutscene (csCtx), no text box that needs the player
    Dialog,          // a text box waits for the player (tap the screen to continue)
    Horse,           // riding Epona
    Title,           // title screen ("Press Start")
    FileSelect,      // file select menus
    Boot,            // app start-up / loading: no game screen yet, nothing to press
    DialogChoice,    // a text box waiting for a choice (stick to choose, tap to confirm)
    Shop,            // talking to / browsing a shopkeeper (EnOssan)
};
constexpr int ContextShift = 18;
constexpr int ContextMask = 15;
// Ocarina input options the overlay needs to know which note buttons to keep.
constexpr int OcarinaFlagDpad = 1 << 22, OcarinaFlagCustom = 1 << 23, OcarinaFlagRightStick = 1 << 24;
// The hook has run at least once (before that the overlay is in its Boot state).
constexpr int StateReady = 1 << 25;
// Navi has something to say (the C-up "Navi" prompt is on screen).
constexpr int StateNaviCalling = 1 << 26;
// "Skip Text" enhancement: holding B (here: holding the screen) fast-forwards dialogue.
constexpr int StateSkipText = 1 << 27;
constexpr int ContextAllBits = (ContextMask << ContextShift) | OcarinaFlagDpad | OcarinaFlagCustom |
                               OcarinaFlagRightStick | StateReady | StateNaviCalling | StateSkipText;

inline int ContextBits(Context context) { return (static_cast<int>(context) & ContextMask) << ContextShift; }
inline int OcarinaFlagBits(bool dpad, bool custom, bool rightStick) {
    return (dpad ? OcarinaFlagDpad : 0) | (custom ? OcarinaFlagCustom : 0) | (rightStick ? OcarinaFlagRightStick : 0);
}
inline Context ContextFromState(int state) {
    const int value = (state >> ContextShift) & ContextMask;
    return value <= static_cast<int>(Context::Shop) ? static_cast<Context>(value) : Context::Normal;
}

// Mirrors GAMEMODE_* (z64save.h); TouchHud.cpp static_asserts the values.
constexpr int GameModeNormal = 0, GameModeTitle = 1, GameModeFileSelect = 2, GameModeCredits = 3;

struct ContextInputs {
    bool inGame = false;            // a PlayState exists
    int gameMode = GameModeNormal;
    bool ocarina = false;           // Link is playing the ocarina
    bool cutscene = false;
    bool dialogActive = false;
    bool dialogChoice = false;      // the waiting text box is a choice (yes/no, ...)
    bool horse = false;
    bool shop = false;              // a shopkeeper is busy with the player (not idle)
    bool paused = false;            // pause menu open
};
struct ContextOptions {
    bool cutscenes = true, ocarina = true, horse = true, title = true, dialogs = true, shops = true;
};

// A text box is "active" from the moment it opens (typing, delays, waiting for input,
// handing over to the next box) until it closes. Only the final state of a box that ends
// by itself (fade, persistent, event) is excluded, so those never keep the screen in dialogue mode.
// Counting the typing phase keeps the buttons from blinking back for every box.
// Values mirror MSGMODE_* and TEXTBOX_ENDTYPE_* (static_asserted in TouchHud.cpp).
inline bool DialogActive(int msgMode, int endType) {
    constexpr int textStart = 0x01, delayedBreak = 0x08, awaitNext = 0x34, done = 0x35;
    constexpr int persistent = 0x40, event = 0x50, fading = 0x60;
    if ((msgMode >= textStart && msgMode <= delayedBreak) || msgMode == awaitNext) return true;
    return msgMode == done && endType != persistent && endType != event && endType != fading;
}

// Choice boxes (TEXTBOX_ENDTYPE_2_CHOICE / _3_CHOICE) need the stick as well as a tap.
inline bool DialogIsChoice(int endType) { return endType == 0x10 || endType == 0x20; }

inline Context ClassifyContext(const ContextInputs& in, const ContextOptions& options) {
    if (options.title) {
        if (in.gameMode == GameModeFileSelect) return Context::FileSelect;
        if (in.gameMode == GameModeTitle) return Context::Title;
    }
    // No game screen yet (start-up, loading): nothing to press.
    if (!in.inGame) return options.title ? Context::Boot : Context::Normal;
    if (options.ocarina && in.ocarina) return Context::Ocarina;
    // Shops come first: their item cursor needs the stick, and the shopkeeper's text boxes are
    // answered with A, so the dialogue "tap anywhere" mode would only get in the way.
    if (options.shops && in.shop && !in.paused) return Context::Shop;
    if (options.dialogs && in.dialogActive && !in.paused)
        return in.dialogChoice ? Context::DialogChoice : Context::Dialog;
    if (options.cutscenes && (in.cutscene || in.gameMode == GameModeCredits)) return Context::Cutscene;
    if (options.horse && in.horse) return Context::Horse;
    return Context::Normal;
}

// Debounce: a new context must persist for `hold` consecutive game updates before
// it is published, so one-frame flickers (a door fade, a text box handing over to
// the next one) never make the buttons blink.
class ContextTracker {
public:
    Context Update(Context raw, int hold = 3) {
        if (raw == current_) { candidate_ = raw; stable_ = 0; return current_; }
        if (raw != candidate_) { candidate_ = raw; stable_ = 1; } else { ++stable_; }
        if (stable_ >= hold) { current_ = candidate_; stable_ = 0; }
        return current_;
    }
    void Reset() { current_ = candidate_ = Context::Boot; stable_ = 0; }
private:
    Context current_ = Context::Boot, candidate_ = Context::Boot;
    int stable_ = 0;
};
}
