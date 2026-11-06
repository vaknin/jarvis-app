# Jarvis 2.0, job D2: the phone (2026-11-06)

Branch only, not on master: waits on Aviv's ux answer (plan section 15 row 9). ux.lock untouched.

- Wire: `crates/tabs-wire` gets D1's additive `IssueBar`, `Tile.issues` and `Body::Jobs.issues` (same shape as the
  jarvis repo's branch jarvis/read-home-jarvis-jarvis-briefs-1793922773). `crates/ffi` carries them as `TabRec.issues`
  (`IssueRec`). Outside android/** because the phone reads the wire through the ffi; additive, serde default.
- Phone (`tabs/Issues.kt`): with open issues the jobs line's slot shows the bar (average step, "N issues · held");
  a tap opens the overview sheet ("Issues from TAB": title, 4-dot stepper, label/try, held, eta, board); a tap on a
  row shows its detail. A Jarvis answer starting "✅ Done: " gets a bolder headline and a dim footer. Without issues
  from the server everything is as today (IssuesTest.withoutIssuesTheTabLooksAsToday).
- Fake: `--ez v2 true` with `--ei tabs 9` gives tab t3 the 2.0 look. Shots: notes/d2-shots.
- Mockup: http://jarvis.localhost:8080/jarvis-picks/2-0-phone.html
