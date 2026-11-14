#include <stdbool.h>
#include <libultraship/bridge/consolevariablebridge.h>
#include "bootcommands.h"
#include "soh/cvar_prefixes.h"

#if defined(__IOS__) && !defined(__TVOS__)
#include "ios/SOHiOSTouchControls.h"
#endif
#ifdef __TVOS__
#include "ios/SOHTVOSFileServer.h"
#endif

void BootCommands_Init() {
    // Clears vars to prevent randomizer menu from being disabled
    CVarClear(CVAR_GENERAL("NewSeedGenerated"));
    CVarClear(CVAR_GENERAL("OnFileSelectNameEntry")); // Clear when soh is killed on the file name entry page
    CVarClear(CVAR_GENERAL("BetterDebugWarpScreenMQMode"));
    CVarClear(CVAR_GENERAL("BetterDebugWarpScreenMQModeScene"));
#if defined(__SWITCH__) || defined(__WIIU__)
    CVarRegisterInteger(CVAR_IMGUI_CONTROLLER_NAV, 1); // always enable controller nav on switch/wii u
#elif defined(__TVOS__)
    CVarSetInteger(CVAR_IMGUI_CONTROLLER_NAV, 1);
    SOHTVOSFileServer_Start();
#elif defined(__IOS__)
    CVarSetInteger(CVAR_IMGUI_CONTROLLER_NAV, 1);
    CVarRegisterInteger(CVAR_SETTING("TouchControls.Disabled"), 0);
    CVarRegisterInteger(CVAR_SETTING("TouchControls.FaceButtonLayout"), 0);
    SOHiOS_SetTouchControlsEnabled(!CVarGetInteger(CVAR_SETTING("TouchControls.Disabled"), 0));
    SOHiOS_SetFaceButtonLayout(CVarGetInteger(CVAR_SETTING("TouchControls.FaceButtonLayout"), 0));
    // The "Classic" touch style was removed. A saved Classic (0) or missing style becomes Modern.
    if (CVarGetInteger(CVAR_SETTING("TouchControls.Style"), 0) < 1 ||
        CVarGetInteger(CVAR_SETTING("TouchControls.Style"), 0) > 3) {
        CVarSetInteger(CVAR_SETTING("TouchControls.Style"), 3);
    }
    SOHiOS_ConfigureIntegratedTouch(CVarGetInteger(CVAR_SETTING("TouchControls.Style"),3),
        CVarGetFloat(CVAR_SETTING("TouchControls.Scale"),1),CVarGetFloat(CVAR_SETTING("TouchControls.Opacity"),.55f),
        CVarGetInteger(CVAR_SETTING("TouchControls.FloatingLeftStick"),1),
        CVarGetInteger(CVAR_SETTING("TouchControls.FloatingRightStick"),1),
        CVarGetInteger(CVAR_SETTING("TouchControls.TransparentSticks"),0));
#endif
}
