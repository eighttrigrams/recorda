# Recording at the playhead, and inserting

Work order. Design done on the host; the implementation is yours. Nothing here
is built.

## Before you start

Read `README.md` first — it is the orientation, and several of its decisions are
load-bearing here rather than merely interesting.

`make start` runs the app on `http://127.0.0.1:3200`; `make stop` stops it. Host
only: AVFoundation needs a window server and CoreAudio, so none of this can run
or be tested in the devbox.

**A recording cannot be driven from the API alone.** The microphone is captured
*in the browser* — ffmpeg's audio input drops about 11% of it, which is why —
so a take started with `curl` will record video, sit at `awaiting-audio`
forever, and never assemble. Drive the UI to make a take. Everything after
that (trim, untrim, crop, export) is fine over HTTP and much quicker to test
that way.

Three cookbook Recipes carry the context that is not in this repo: the ffmpeg
capture defect and its fix, why two HTML media elements cannot be synced (and
the postscript about hunting a capture bug in the player for three rounds), and
how to prove an audio capture is losing samples. Search cookbook for `recorda`.

## Where things stand

A project is one video made of numbered **segments**, each captured whole and
never modified afterwards. What you play and export is an **assembly**,
concatenated from the live segments. A trim writes `:out` on the segment holding
the playhead and marks later ones `:dropped`; every file stays whole, so it is
reversible.

The property everything rests on: **nothing re-encodes.** The capture produces
h264 with **no B-frames** (measured: 13 I-frames and 136 P-frames in a 5 s take,
no B) and a keyframe roughly every **0.4 s**. That makes two operations exact by
stream copy — cutting a segment's *tail*, and joining segments end to end.

Read `src/clj/et/rec/assemble.clj` first; it is where all of this lives.

## What is wanted

Three recording modes. Today only the first exists.

| mode | meaning |
|---|---|
| **Append** | new material goes on the end. Today's behaviour, and stays the default. |
| **At playhead** | new material *replaces* everything from the playhead onward. |
| **Insert** | new material is spliced in *at* the playhead; what followed is kept and comes after. |

## "At playhead" is nearly free

It is exactly `trim-at!` followed by a normal append, and both already exist.

Do it as **one server action** rather than two client calls
(`POST /api/recordings/:id/record/start?at=<seconds>`), so that a failure
between the two cannot leave the project trimmed with nothing recorded to
replace what was cut. Trim is reversible, but a half-done operation is still a
bad thing to hand someone.

That is most of the value, and it is a small change. Consider shipping it alone.

## "Insert" needs the model to change

A segment list cannot express it. Ordering is currently implied by `:n`, and a
segment can only be cut at its end.

**Keep `:segments` as the physical inventory and add `:clips` as the
arrangement:**

```clojure
:segments [{:n 1 :duration 19.633} {:n 2 :duration 17.133}]
:clips    [{:seg 1 :in 0.0 :out 8.0}
           {:seg 2}                      ; whole
           {:seg 1 :in 8.0}]             ; the rest of the first sitting
```

A trim becomes a clip operation; an insert splits one clip in two and puts the
new one between. This subsumes what exists rather than replacing it.

**Migration is free.** A project with no `:clips` reads as one clip per live
segment, honouring its `:out`. So the reader can land before anything writes
clips, and existing projects keep working untouched.

## The hard part, and the thing to decide first

**A clip that starts mid-segment cannot be stream-copied from an arbitrary
point.** Copy can only begin at a keyframe. So the *resume* clip after an insert
lands on a keyframe — up to ~0.4 s from where the playhead was.

Three ways out:

**(a) Snap, and say so.** Cut at the nearest keyframe and move the playhead to
where the split actually landed, so the UI never claims an accuracy it does not
have. One line of ffmpeg (`-ss` *before* `-i`, with `-c copy`), no re-encoding,
nothing else changes. **Start here.**

**(b) Re-encode the tail piece.** Frame-accurate, and only one short piece is
re-encoded. The risk is that concat-by-copy needs matching stream parameters —
a piece re-encoded with different SPS/PPS may not join cleanly to its
neighbours. If you try this, pin profile, level and pixel format explicitly and
verify the join, do not assume it.

**(c) Shorten the GOP at capture** (`-g` on the capture command) to buy finer
granularity. Costs bitrate, changes every future recording, and does nothing for
material already recorded. Probably not worth it for 0.4 s.

### The trap in (a)

Audio has no keyframe constraint — WAV cuts are sample-exact. So it is tempting
to cut the audio at the requested time and the video at the snapped one.

**Do not.** Cut the audio at the time the *video actually landed on*. Otherwise
the two drift apart by up to 0.4 s at every insert, and the drift accumulates
across inserts. This is the single easiest way to reintroduce the class of bug
that cost this project most of a day.

## Code touchpoints

- `assemble.clj` — `assemble!` walks clips instead of segments; `part!` gains an
  `:in` and needs `-ss` before `-i`; the audio part must be cut at the video's
  realised start, not the requested one.
- `assemble.clj` — `trim-at!` rewritten as a clip operation; add `insert-at!`.
- `capture.clj` — `start!` takes a mode and an optional position.
- `server/handlers.clj`, `server.clj` — the mode on the start route.
- `ui/views/recorder.cljs` — a mode control beside Record. Default Append.
- `ui/views/player.cljs` — when the mode is not Append, show *where* the new
  material will go, before it is recorded.

## Verify these, do not assume them

- A concat whose parts were cut with `-ss` joins without a stall, a freeze or
  duplicated frames at the seam. `-avoid_negative_ts make_zero` may be needed.
- Audio and video still end at the same length after an insert, and the assembly
  duration equals the sum of the clip lengths.
- Insert at `0.0` (prepend) and at the very end (should behave as append).
- Two inserts in the same segment, which produces three clips from one file.
- Undo still works: clips are derived, the files are not touched, so clearing
  `:clips` should return the project to plain appended segments.

## Not in scope

Reordering clips, and deleting from the middle. The clip model makes both
possible, and neither is asked for — a screencast is recorded roughly in order.
Do not build them on spec.
