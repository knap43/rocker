# Rocker

Teach the volume buttons to pause, resume and skip whatever is playing — screen on or off.
Kotlin, Jetpack Compose, Material 3; same dark, amber-accented look as Journal, Files, Reader and
Macros.

Each key has three gestures — **press**, **double press**, **hold** — plus **both keys together**,
and each can be set to *change volume* (what the button normally does), *play / pause*,
*next track*, *previous track* or *do nothing*. Out of the box: hold up skips forward, hold down
skips back, both together play or pause, and everything else is plain volume.

## Build

```fish
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The workflow in `.github/workflows/build.yml` builds debug and release APKs on every push to
`main`; download them from the run's artifacts.

## Setting up

1. Open Rocker and tap **Open** on *Turn on Rocker*, then switch it on under Accessibility →
   installed (or downloaded) apps.
2. Android 13+ greys that switch out for sideloaded apps. Open **App info → ⋮ → Allow restricted
   settings**, then try again.
3. On realme UI, also allow unrestricted battery use, or the phone may freeze Rocker with the
   screen off.

The dot at the top turns amber once the service listens; the line under it shows the last gesture
it recognised, which is the quickest way to try out a mapping.

## How it works

Everything runs inside one accessibility service (`engine/RockerService.kt`). An enabled service
is kept alive by Android, so there is no foreground notification.

- **Screen on — `KeyGestures`.** The service filters raw key events, so it sees every down and up
  and can swallow them. A key whose gestures are all plain volume is let through untouched. A key
  with any gesture assigned is taken over entirely, and its ordinary presses and holds are replayed
  as volume steps.
- **Hiding the volume popup.** With *Hide the volume popup* on, plain-volume keys are swallowed as
  well and replayed at once — a step on each press and each auto-repeat — through `AudioManager`
  without `FLAG_SHOW_UI`, so the volume changes and the system panel never appears. While the phone
  rings or is on a call the keys are left alone, so they can still silence the ringer.
- **Screen off — `ScreenOffSession` + `VolumeGestures`.** With the screen off, Android gives volume
  keys to the media session that most recently started playing, not to accessibility services.
  While the screen is off, Rocker runs a session that claims to be playing (and plays nothing),
  with its volume routed to a `VolumeProvider`, so the presses come to Rocker. All it gets are
  "raise" and "lower" nudges, so a hold is recognised from the key's auto-repeat, and a release
  nudge where Android sends one. Volume steps are passed on to the media stream.
- **Media commands** go out as media-button presses through `AudioManager`, which Android routes
  to whichever app last played sound. No notification access is needed.

## Limits worth knowing

- **Both keys together** works with the screen on only. With it off, Android reports one key at a
  time.
- **With the screen off, ordinary presses wait** until Rocker knows they aren't the start of a
  hold: about the key-repeat delay (~0.5 s), or less where Android reports the release.
- **With the screen off, the keys change media volume only**, not ringer volume. Turn off
  *Work with the screen off* if that matters.
- **The player can win the keys back.** When it resumes after a pause, Android hands it the volume
  keys again. Rocker re-claims them after each command and every 15 s, so a gesture right after
  resuming can occasionally fall through as a plain volume change.
- **Phone makers can disable the screen-off route.** It relies on remote-volume sessions receiving
  volume keys, which stock Android and LineageOS allow (`config_volumeAdjustmentForRemoteGroupSessions`).
  If screen-off gestures do nothing on a phone, that is the likely reason.
