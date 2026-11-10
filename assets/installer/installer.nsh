; Keepfin installer additions (electron-builder nsis.include): the look (welcome page, header line, sharp art on any screen
; scale) and, for installs for all users, the Private-network firewall rule at the end.

; Without this Windows draws the installer at 100% and stretches the whole window on 125-300% screens, which blurs the
; text and the art. Aware of the scale, Windows lays the dialogs out at full resolution and the art below is loaded at
; the matching size (scripts/build-installer-art.mjs renders sidebar/header at 100-300%).
ManifestDPIAware true

; electron-builder includes this file before MUI2.nsh, and the functions below use LogicLib and nsDialogs (both guard
; against a second include).
!include LogicLib.nsh
!include nsDialogs.nsh

; The screen scale's art suffix ("" for 100%, "-125" ... "-300") and the scale in percent.
Var keepfinArt
Var keepfinScale
; The header bitmap loaded for this scale (kept for the life of the window).
Var keepfinHeader

; Sets $keepfinScale from the screen's DPI and $keepfinArt to the smallest rendered size that is at least as large,
; then extracts that sidebar and header to $PLUGINSDIR (kf-sidebar.bmp, kf-header.bmp). Shared by installer and uninstaller.
!macro keepfinArtFile SUFFIX
  ${If} $keepfinArt == "${SUFFIX}"
    File "/oname=$PLUGINSDIR\kf-sidebar.bmp" "${PROJECT_DIR}\assets\installer\sidebar${SUFFIX}.bmp"
    File "/oname=$PLUGINSDIR\kf-header.bmp" "${PROJECT_DIR}\assets\installer\header${SUFFIX}.bmp"
  ${EndIf}
!macroend

; The welcome and finish pages' controls, declared here (exactly as MUI2's Pages\Welcome.nsh and Finish.nsh would) so
; the show functions below can use them; MUI skips its own declarations once these interface defines exist.
!ifndef MUI_WELCOMEPAGE_INTERFACE
  !define MUI_WELCOMEPAGE_INTERFACE
  Var mui.WelcomePage
  Var mui.WelcomePage.Image
  Var mui.WelcomePage.Image.Bitmap
  Var mui.WelcomePage.Title
  Var mui.WelcomePage.Title.Font
  Var mui.WelcomePage.Text
!endif
!ifndef MUI_FINISHPAGE_INTERFACE
  !define MUI_FINISHPAGE_INTERFACE
  Var mui.FinishPage
  Var mui.FinishPage.Image
  Var mui.FinishPage.Image.Bitmap
  Var mui.FinishPage.Title
  Var mui.FinishPage.Title.Font
  Var mui.FinishPage.Text
!endif

!macro keepfinArtFunction UN
Function ${UN}keepfinLoadArt
  Push $0
  Push $1
  System::Call 'user32::GetDC(p 0) p .r0'
  ; LOGPIXELSX
  System::Call 'gdi32::GetDeviceCaps(p r0, i 88) i .r1'
  System::Call 'user32::ReleaseDC(p 0, p r0)'
  IntOp $1 $1 * 100
  IntOp $1 $1 / 96
  StrCpy $keepfinScale $1
  StrCpy $keepfinArt ""
  ${If} $1 > 250
    StrCpy $keepfinArt "-300"
  ${ElseIf} $1 > 200
    StrCpy $keepfinArt "-250"
  ${ElseIf} $1 > 175
    StrCpy $keepfinArt "-200"
  ${ElseIf} $1 > 150
    StrCpy $keepfinArt "-175"
  ${ElseIf} $1 > 125
    StrCpy $keepfinArt "-150"
  ${ElseIf} $1 > 100
    StrCpy $keepfinArt "-125"
  ${EndIf}
  InitPluginsDir
  !insertmacro keepfinArtFile ""
  !insertmacro keepfinArtFile "-125"
  !insertmacro keepfinArtFile "-150"
  !insertmacro keepfinArtFile "-175"
  !insertmacro keepfinArtFile "-200"
  !insertmacro keepfinArtFile "-250"
  !insertmacro keepfinArtFile "-300"
  ; The header image (control 1046) was loaded by MUI at 100%; replace it with the art for this scale. (SetBrandingImage
  ; cannot be used here: this file is compiled before MUI picks its header UI.)
  ${If} $keepfinArt != ""
    GetDlgItem $0 $HWNDPARENT 1046
    ${NSD_SetStretchedImage} $0 "$PLUGINSDIR\kf-header.bmp" $keepfinHeader
  ${EndIf}
  Pop $1
  Pop $0
FunctionEnd

; The welcome and finish pages' sidebar: MUI loaded the 100% bitmap; swap in the one for this scale.
Function ${UN}keepfinSidebarShow
  ${If} $keepfinArt != ""
    ${NSD_FreeImage} $mui.WelcomePage.Image.Bitmap
    ${NSD_SetStretchedImage} $mui.WelcomePage.Image "$PLUGINSDIR\kf-sidebar.bmp" $mui.WelcomePage.Image.Bitmap
  ${EndIf}
FunctionEnd

Function ${UN}keepfinFinishShow
  ${If} $keepfinArt != ""
    ${NSD_FreeImage} $mui.FinishPage.Image.Bitmap
    ${NSD_SetStretchedImage} $mui.FinishPage.Image "$PLUGINSDIR\kf-sidebar.bmp" $mui.FinishPage.Image.Bitmap
  ${EndIf}
FunctionEnd
!macroend

!ifndef BUILD_UNINSTALLER

!insertmacro keepfinArtFunction ""

; Updates: $INSTDIR already points at the installed Keepfin, so Keepfin.exe there means this run is an update.
!include StrContains.nsh

; The scope to keep without asking ("all" or "CurrentUser"), or "" to show the "who is it for" page.
Var keepfinScope

; Expanded inside customWelcomePage, because these use variables electron-builder declares after this file.
!macro keepfinUpdateFunctions
Function keepfinFindScope
  StrCpy $keepfinScope ""
  ${If} $perUserInstallationFolder != ""
  ${AndIf} $perMachineInstallationFolder == ""
    StrCpy $keepfinScope "CurrentUser"
  ${ElseIf} $perMachineInstallationFolder != ""
  ${AndIf} $perUserInstallationFolder == ""
  ${AndIf} ${UAC_IsAdmin}
    StrCpy $keepfinScope "all"
  ${EndIf}
FunctionEnd

Function keepfinWelcomeShow
  Call keepfinSidebarShow
  ${If} ${FileExists} "$INSTDIR\${APP_EXECUTABLE_FILENAME}"
    Call keepfinFindScope
    StrCpy $0 "Next"
    ${If} $keepfinScope != ""
      StrCpy $0 "Update"
      GetDlgItem $1 $HWNDPARENT 1
      SendMessage $1 ${WM_SETTEXT} 0 "STR:Update"
    ${EndIf}
    SendMessage $mui.WelcomePage.Title ${WM_SETTEXT} 0 "STR:Update Keepfin"
    SendMessage $mui.WelcomePage.Text ${WM_SETTEXT} 0 "STR:Setup will update Keepfin to version ${VERSION} in $INSTDIR.$\r$\n$\r$\nIf Keepfin is open, close it first. Your library and settings are kept.$\r$\n$\r$\nClick $0 to continue."
  ${EndIf}
FunctionEnd

Function keepfinDirectoryPre
  ${If} ${FileExists} "$INSTDIR\${APP_EXECUTABLE_FILENAME}"
    Abort
  ${EndIf}
FunctionEnd

; Adds a Keepfin subfolder when the chosen folder lacks one.
Function keepfinInstFilesPre
  ${StrContains} $0 "${APP_FILENAME}" $INSTDIR
  ${If} $0 == ""
    StrCpy $INSTDIR "$INSTDIR\${APP_FILENAME}"
  ${EndIf}
FunctionEnd
!macroend

!macro customInstallMode
  Call keepfinFindScope
  ${If} $keepfinScope == "CurrentUser"
    StrCpy $isForceCurrentInstall "1"
  ${ElseIf} $keepfinScope == "all"
    StrCpy $isForceMachineInstall "1"
  ${EndIf}
!macroend

; The second PRE define applies to the files page that follows.
!macro customPageAfterChangeDir
  !define MUI_PAGE_CUSTOMFUNCTION_PRE keepfinDirectoryPre
  !insertmacro MUI_PAGE_DIRECTORY
  !define MUI_PAGE_CUSTOMFUNCTION_PRE keepfinInstFilesPre
!macroend

; A welcome page (with the branded sidebar) before the usual pages, skipped when an update runs the installer.
!macro customWelcomePage
  !insertmacro keepfinUpdateFunctions
  !define MUI_CUSTOMFUNCTION_GUIINIT keepfinGuiInit
  !insertmacro skipPageIfUpdated
  !define MUI_PAGE_CUSTOMFUNCTION_SHOW keepfinWelcomeShow
  !define MUI_WELCOMEPAGE_TEXT "Setup will install Keepfin on this computer.$\r$\n$\r$\nIf Keepfin is open, close it first. Your library and settings are kept.$\r$\n$\r$\nClick Next to continue."
  !insertmacro MUI_PAGE_WELCOME
!macroend

; electron-builder's own finish page (assistedInstaller.nsh), plus the sidebar for this screen scale.
!macro customFinishPage
  !ifndef HIDE_RUN_AFTER_FINISH
    Function StartApp
      ${if} ${isUpdated}
        StrCpy $1 "--updated"
      ${else}
        StrCpy $1 ""
      ${endif}
      ${StdUtils.ExecShellAsUser} $0 "$launchLink" "open" "$1"
    FunctionEnd

    !define MUI_FINISHPAGE_RUN
    !define MUI_FINISHPAGE_RUN_FUNCTION "StartApp"
  !endif
  !define MUI_PAGE_CUSTOMFUNCTION_SHOW keepfinFinishShow
  !insertmacro MUI_PAGE_FINISH
!macroend

; The line under the header (control 1036 in the UI resource, found by enumerating the dialog) is a system etched line
; whose colour cannot be changed, so it is hidden and a 2 px cobalt line (scaled with the screen) is placed where it was.
; The new line sits at the bottom of the z-order, so the full-window panel of the welcome and finish pages covers it there.
Function keepfinGuiInit
  Call keepfinLoadArt
  GetDlgItem $0 $HWNDPARENT 1036
  System::Call '*(i, i, i, i) p .r1'
  System::Call 'user32::GetWindowRect(p r0, p r1)'
  System::Call '*$1(i .r2, i .r3, i .r4, i .r5)'
  System::Free $1
  IntOp $6 $4 - $2
  System::Call '*(i r2, i r3) p .r1'
  System::Call 'user32::ScreenToClient(p $HWNDPARENT, p r1)'
  System::Call '*$1(i .r2, i .r3)'
  System::Free $1
  ShowWindow $0 0
  ; 2 px at 100%, rounded at other scales
  IntOp $8 $keepfinScale * 2
  IntOp $8 $8 + 50
  IntOp $8 $8 / 100
  ; WS_CHILD | WS_VISIBLE static, painted with the colour set below
  System::Call 'user32::CreateWindowExW(i 0, w "STATIC", w "", i 0x50000000, i r2, i r3, i r6, i r8, p $HWNDPARENT, p 1999, p 0, p 0) p .r7'
  SetCtlColors $7 "2855CE" "2855CE"
  ; HWND_BOTTOM; SWP_NOSIZE | SWP_NOMOVE | SWP_NOACTIVATE
  System::Call 'user32::SetWindowPos(p r7, p 1, i 0, i 0, i 0, i 0, i 0x13)'
FunctionEnd

!else

!insertmacro keepfinArtFunction "un."

; The uninstaller's welcome page and its header art, at this screen's scale.
!macro customUnWelcomePage
  !define MUI_CUSTOMFUNCTION_UNGUIINIT un.keepfinLoadArt
  !define MUI_PAGE_CUSTOMFUNCTION_SHOW un.keepfinSidebarShow
  !insertmacro MUI_UNPAGE_WELCOME
!macroend

; electron-builder inserts this right before MUI_UNPAGE_FINISH and has no hook of its own for that page, so the show
; function is defined here and applies to the finish page that follows.
!macro customUninstallPage
  !define MUI_PAGE_CUSTOMFUNCTION_SHOW un.keepfinFinishShow
!macroend

!endif

; Devices on the network (docs/plans/KEEPFIN-NETWORK-SPEC.md, docs/plans/DEVICES-DISCOVERY.md): Keepfin listens on TCP 47215 so paired devices can reach it and
; answers device discovery on UDP 5353 (mDNS). When Keepfin is installed for all users (the installer is elevated then) two inbound allow rules for Keepfin.exe are
; added for every network profile (Windows calls a new network Public, so a Private-only rule would block the phone), and removed again on uninstall. The pairing
; window, pin checks, rate limits and mutual TLS protect the port, not the network category. A per-user install is not elevated and cannot add a firewall rule:
; nothing is added and nothing fails; the app's Devices page then offers "Allow Keepfin", which adds the same two rules after one Windows permission prompt.
; Updates replace the rules.
!macro customInstall
  ${If} $installMode == "all"
    nsExec::Exec '"$SYSDIR\netsh.exe" advfirewall firewall delete rule name="Keepfin devices" program="$INSTDIR\${APP_EXECUTABLE_FILENAME}"'
    Pop $0
    nsExec::Exec '"$SYSDIR\netsh.exe" advfirewall firewall delete rule name="Keepfin discovery" program="$INSTDIR\${APP_EXECUTABLE_FILENAME}"'
    Pop $0
    nsExec::Exec '"$SYSDIR\netsh.exe" advfirewall firewall add rule name="Keepfin devices" dir=in action=allow program="$INSTDIR\${APP_EXECUTABLE_FILENAME}" protocol=TCP localport=47215 profile=any'
    Pop $0
    nsExec::Exec '"$SYSDIR\netsh.exe" advfirewall firewall add rule name="Keepfin discovery" dir=in action=allow program="$INSTDIR\${APP_EXECUTABLE_FILENAME}" protocol=UDP localport=5353 profile=any'
    Pop $0
  ${EndIf}
!macroend

!macro customUnInstall
  ${If} $installMode == "all"
    nsExec::Exec '"$SYSDIR\netsh.exe" advfirewall firewall delete rule name="Keepfin devices" program="$INSTDIR\${APP_EXECUTABLE_FILENAME}"'
    Pop $0
    nsExec::Exec '"$SYSDIR\netsh.exe" advfirewall firewall delete rule name="Keepfin discovery" program="$INSTDIR\${APP_EXECUTABLE_FILENAME}"'
    Pop $0
  ${EndIf}
!macroend