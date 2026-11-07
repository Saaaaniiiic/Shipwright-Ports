#pragma once
#include <cstdint>

namespace TouchHud {
// What the player is doing right now, as far as the touch overlay cares.
// Published to the Android/iOS view in state bits 18..20 (see TouchHud.cpp).
enum class Context : int {
    Normal = 0,
    Ocarina,         // playing the ocarina
    Cutscene,        // scripted cutscene (csCtx), no text box that needs the player
    CutsceneDialog,  // cutscene whose current text box waits for the player
    Horse,           // riding Epona
    Title,           // title screen ("Press Start")
    FileSelect,      // file select menus
};
constexpr int ContextShift = 18;
constexpr int ContextMask = 7;
// Ocarina input options the overlay needs to know which note buttons to keep.
constexpr int OcarinaFlagDpad = 1 << 21, OcarinaFlagCustom = 1 << 22, OcarinaFlagRightStick = 1 << 23;
constexpr int ContextAllBits = (ContextMask << ContextShift) | OcarinaFlagDpad | OcarinaFlagCustom | OcarinaFlagRightStick;

inline int ContextBits(Context context) { return (static_cast<int>(context) & ContextMask) << ContextShift; }
inline int OcarinaFlagBits(bool dpad, bool custom, bool rightStick) {
    return (dpad ? OcarinaFlagDpad : 0) | (custom ? OcarinaFlagCustom : 0) | (rightStick ? OcarinaFlagRightStick : 0);
}
inline Context ContextFromState(int state) {
    const int value = (state >> ContextShift) & ContextMask;
    return value <= static_cast<int>(Context::FileSelect) ? static_cast<Context>(value) : Context::Normal;
}

// Mirrors GAMEMODE_* (z64save.h); TouchHud.cpp static_asserts the values.
constexpr int GameModeNormal = 0, GameModeTitle = 1, GameModeFileSelect = 2, GameModeCredits = 3;

struct ContextInputs {
    bool inGame = false;            // a PlayState exists
    int gameMode = GameModeNormal;
    bool ocarina = false;           // Link is playing the ocarina
    bool cutscene = false;
    bool dialogNeedsInput = false;
    bool horse = false;
};
struct ContextOptions {
    bool cutscenes = true, ocarina = true, horse = true, title = true;
};

// A text box needs the player only once it has finished typing and is waiting
// (MSGMODE_TEXT_AWAIT_INPUT / _AWAIT_NEXT / _DONE). A box that is still typing,
// or that ends by itself (fade, persistent, event), never asks for A.
// Values mirror MSGMODE_* and TEXTBOX_ENDTYPE_* (static_asserted in TouchHud.cpp).
inline bool DialogNeedsInput(int msgMode, int endType) {
    constexpr int awaitInput = 0x07, awaitNext = 0x34, done = 0x35;
    constexpr int persistent = 0x40, event = 0x50, fading = 0x60;
    if (msgMode == awaitInput || msgMode == awaitNext) return true;
    return msgMode == done && endType != persistent && endType != event && endType != fading;
}

inline Context ClassifyContext(const ContextInputs& in, const ContextOptions& options) {
    if (options.title) {
        if (in.gameMode == GameModeFileSelect) return Context::FileSelect;
        if (in.gameMode == GameModeTitle) return Context::Title;
    }
    if (!in.inGame) return Context::Normal;
    if (options.ocarina && in.ocarina) return Context::Ocarina;
    if (options.cutscenes && (in.cutscene || in.gameMode == GameModeCredits))
        return in.dialogNeedsInput ? Context::CutsceneDialog : Context::Cutscene;
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
    void Reset() { current_ = candidate_ = Context::Normal; stable_ = 0; }
private:
    Context current_ = Context::Normal, candidate_ = Context::Normal;
    int stable_ = 0;
};
}
