# Plan: one window snapshot for "is P the app the user is in" (#76, #71)

Branch: `worktree-agent-a05986b86d10adc28` (lane `nudge-window-snapshot`). Status: in progress.

## Root cause shared by both issues

The service decides "package P is the app the user is in" from the EVENT's package and never
checks the WINDOW. PiP (#19) got a one-off patch (`readPipWindows`). Two more shapes of the same
mistake:

- **#76** (Samsung, SwiftKey): with the keyboard up, a window from ANOTHER package (inline autofill
  suggestions rendered in the IME window, the Samsung clipboard panel, Galaxy AI writing tools)
  fires a window event, classifies `AppWindow(other)`, and starts the sitting's away clock. Typing
  is IME events, which return at `Transient`, so nothing cancels the clock. Two minutes of typing,
  close the keyboard, the app's window event reads as a return past the window: grant revoked,
  block, repeated blocks feed auto-kick.
- **#71** (Huawei freeform): the blocked app in a floating window sits ABOVE our fullscreen overlay.
  Each touch fires `WINDOW_STATE_CHANGED` for it, `isGenuineBypass` says yes, the overlay is marked
  inactive and relaunched underneath, forever. The block never covers anything.

## Design

1. `domain/windows/WindowSnapshot` (pure): per window id, kind (APPLICATION / INPUT_METHOD / SYSTEM
   / ACCESSIBILITY_OVERLAY / OTHER), owner package (application windows only), active, focused,
   PiP, bounds, plus the display size. Queries: `kindOf(windowId)`,
   `ownsFocusedApplicationWindow(pkg)`, `focusIsElsewhere(pkg)`, `floatingBoundsOf(pkg)`,
   `pipWindows()`.
2. `service/WindowSnapshotReader` (thin adapter, like `PipWindowProbe`): reads `windows`, resolves
   the owner of each APPLICATION window once per window id (cached; ids are stable for a window's
   life), reads the display size. Fails soft to null = "no window evidence" = today's behaviour.
   Refreshed on every `WINDOW_STATE_CHANGED` (A11yMS computes windows before dispatching it), and
   throttled with a trailing refresh for `WINDOWS_CHANGED` (including the null-package ones the
   dispatch used to drop before reading anything).
3. `EventClassifier` takes the snapshot as a constructor provider (no default). A would-be
   `AppWindow(P)` becomes `Transient` only on POSITIVE window evidence:
   - the event's window id is a known non-application window (IME, system, a11y overlay), or
   - P owns application windows, none active/focused, while ANOTHER package's application window
     is active/focused.
   No snapshot, an unknown window id, or P absent from the list: unchanged. Every ENABLED IME is
   transient (shared `util/InputMethods` helper, also used by `NukeSafetyFloor`); honeyboard added
   to the static floor; the companion copy `isTransientNonAppPackage` is deleted.
4. #71: `BlockLaunchGate.isGenuineBypass` gets `floating`: a non-fullscreen application window
   cannot have bypassed a fullscreen overlay. Floating events fall through to evaluation instead of
   relaunching. `launchBlockOverlay` (the one launch site) covers a floating target with a
   touch-consuming `TYPE_ACCESSIBILITY_OVERLAY` (`FloatingWindowCoverManager`, TabCover's
   approach), presses BACK once per window while it is focused, and skips relaunching an overlay
   that is already up. The cover is maintained on every snapshot refresh and removed when the
   window goes away or turns fullscreen, a grant/emergency pass exists, the decision is ALLOW,
   Nudge is disabled, or the screen goes off.
5. Tests (L1): snapshot queries, classifier decisions (IME-only, autofill panel from another pkg in
   the IME window, unfocused panel, freeform blocked window, full-screen normal switch still a
   departure, no window info fails open), the bypass gate with `floating`, the cover policy.
