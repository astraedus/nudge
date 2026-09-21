---
name: Bug report
about: Something in Nudge isn't working the way it should
title: ''
labels: bug
assignees: ''
---

<!-- You don't need to fill in every box. Even half of this is more than most reports have. -->

## What happened

<!-- One or two sentences. What did you expect, and what did Nudge do instead? -->

## Steps

1.
2.
3.

## Your setup

- **Nudge version:** <!-- Settings → About → Version, e.g. "1.17.1 (build 87)" -->
- **Android version and phone:** <!-- e.g. "Android 14, Pixel 7a" or "Android 9, Samsung A10" -->
- **The app you were blocking:** <!-- the app Nudge was supposed to block — skip if it wasn't about a specific app -->
- **Block mode:** <!-- Hard block / delay / breathing / daily limit / schedule / website — if you know -->

---

## If this is about blocking or the accessibility service

This is the part of Nudge we often cannot reproduce on our own phone: the accessibility event
stream is different on every device, and different again per app and per app version. A log of
what your phone actually sent Nudge is worth more than any description — including ours.

Cases where this matters: a block didn't appear, appeared over the wrong app, appeared twice,
the delay countdown restarted or got skipped, the interaction counter counted wrong, blocking
stopped working after a while, or "I changed my mind" needed more than one tap.

**Turn on debug logging** (it writes to the phone's system log only — Nudge still sends nothing
anywhere, it has no internet permission):

1. Open Nudge → **Settings**
2. Scroll to **About** and tap **Version** seven times — a "Developer Options" section appears
3. Turn on **Debug Logging**
4. Reproduce the bug

**Then grab the log, if you're able to.** This needs a computer with
[the Android platform tools](https://developer.android.com/tools/adb) and USB debugging turned on.
If that isn't something you have set up, skip it and just describe what happened — that is
genuinely fine, and most reports arrive that way.

```bash
# everything Nudge logged (attach this file)
adb logcat -d --pid=$(adb shell pidof dev.astraedus.nudge) > nudge-log.txt

# accessibility events only — smaller, and the most useful part for a blocking bug
adb logcat -s NudgeA11yTrace:I > nudge-events.txt
```

Attach the file (drag it into this issue). Skim it first if you like: it contains the package
names of whatever was in the foreground while you were recording, and nothing else about you.

Turn Debug Logging back off when you're done.

<!--
Maintainer note: a report in this category gets a committed capture fixture before a fix is
designed — `scripts/a11y-capture.sh <name>` over the repro. See docs/testing-strategy.md, rule (c).
-->
