# Step 4 working notes (not for keeps: folded into LOOK.md's phone section at the end)

## Library facts, checked on Google Maven 2026-10-31
- Compose BOM 2026.09.00 (newest): ui/foundation/animation 1.12.1, **material3 1.4.0**. Stable M3 is 1.4.0;
  1.5.0-beta02 is the newest pre-release. Stay on the BOM's stable; take expressive ideas, not alpha APIs.
- navigationevent 1.2.0, baseline profile plugin / macro benchmark 1.5.0, graphics-shapes 1.1.0,
  Roborazzi 1.77.0, Robolectric 4.17.
- Full-screen predictive back spec (design guide): exit scale 100→90%, enter 110→100, exit fade done by 35%,
  interpolator (.1,.1,0,1); cancel returns to size. Shared-element variant: scale min 90%, 8 dp margin.
- Shared elements doc: sharedElement/sharedBounds; no shape-to-shape, ContentScale snaps; says nothing about
  predictive back or seekable. So: zoom is ours, one Animatable driven by tap, pinch and back.
- Insets: adjustResize + enableEdgeToEdge, imePadding follows the keyboard, safeDrawing, tappable bottom.

## Named apps: primary facts found
- Linear: native Swift/Kotlin; quick composer "obsessive focus on speed"; transitions 0.1-0.35 s; updates local first,
  no spinners.
- Things 3: Magic Plus at lower right, thumb reach; liquid deform on drag; custom animation toolkit; floating button
  morphs into the card; haptic when picking up.
- Arc Search on Android: tab list like the system's recents, satisfying fling; keyboard open at start so typing is
  first; reviewers hated low density past 10 tabs, so the wall must stay dense at 14.
- Telegram, ChatGPT, Claude: searches found nothing primary. Anything said about them is from what the apps do,
  not from a source.

## Decisions so far
- Own grid, own zoom, own theme object (LOOK's roles): Material 3 only for text field pieces and ripple where handy.
- Wall grid picks columns by tile shape, not a table.
- Wire: copy of tabs/wire in crates/tabs-wire (jarvis repo private, jarvis-app public). Gate: new handshake + unix socket.
