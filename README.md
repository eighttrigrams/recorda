# recorda

Records the small screen and the Scarlett at the same time, and gives you back
**two tracks** — a video file and an audio file — laid out one above the other
so you can see both at once.

```
make start     # http://127.0.0.1:3200
make stop
make devices   # what ffmpeg can see right now, and which of it recorda would use
```

Recording and playback are what exist today. Editing is the point of the
shape, and is not built yet — see the end.

## Two files, not one

The obvious way to record a screen with narration is one file with two streams
in it, and that is what almost everything does. recorda writes `video.mp4` and
`audio.wav` instead, because the two things you want to do to a screencast
afterwards are different things:

- the picture gets **cut** — dead air removed, a fumbled minute dropped
- the sound gets **treated** — levelled, de-essed, noise gated, or taken into a
  DAW entirely and brought back

Muxed into one container those are the same operation on the same object, and
doing either means re-encoding both. Kept apart, the WAV opens in anything and
the MP4 never has to be decoded to do something that only concerns the audio.

They are still recorded by **one ffmpeg, from one AVFoundation session**, as
`5:1` — one input, both streams. Two inputs would be two device sessions with
two clocks and nothing tying them together.

## The sound does not come from ffmpeg

**ffmpeg's AVFoundation audio input drops about 11% of what it is given** on
this machine, in every version tried. It discards roughly one 512-sample buffer
ten times a second and concatenates what survives, so speech comes out with
pieces cut from it. It sounds like jitter, and it is baked into the file — no
amount of work on the player can touch it.

Measured with a click train played at exactly 100.000 ms, through the
Scarlett's digital loopback:

    played      100.000 ms
    ffmpeg       88.5 ms, and 89.7 ms on a second run
    deficit      ~10.7 ms = 512 samples at 48 kHz

Ruled out one at a time: the screen capture (audio-only loses the same), the
interface (other input devices lose too), the ffmpeg version (7.1.5 and 9.0.1
identical), `-thread_queue_size`, `-drop_late_frames`, channel count, the `pan`
filter, and the player used as the test source. **Audacity records the same
interface cleanly**, which is what says the hardware is healthy and ffmpeg's
input is not.

So **the browser records the microphone** — `getUserMedia` into an
`AudioWorklet`, in `et.rec.ui.mic` — and ffmpeg keeps only the screen, which it
captures perfectly well. Over a five minute capture the browser lost **91 ms**
in total, 0.03%, against ffmpeg's 11% in every second.

An AudioWorklet and not a `ScriptProcessorNode`: the latter is deprecated
precisely because it runs on the main thread and drops audio whenever the page
is busy, and a screen recorder's page is not idle.

## Which means the two recorders must be lined up

They start independently, so something has to measure the gap rather than
assume it.

**The microphone leads.** It is opened first, and the screen capture does not
start until real audio has arrived — not when the stream was requested, which is
a different and much earlier instant. An interface has a clock of its own to
start, and on this machine that has been over a second. Starting the picture
during that window gives you opening seconds with no sound under them.

The server answers the start request with `video-started-at`, and earns that
number rather than guessing it: it waits until ffmpeg is actually writing frames
before reporting. Spawning ffmpeg and calling that the start would count its own
warm-up — opening the display, negotiating a pixel format, bringing up the
encoder — as recorded video that does not exist.

The difference between the two is the **lead**, cut off the front of the audio
when the browser uploads it. A real take measured 1.449 s. After that the WAV's
first sample is the video's first frame, with nothing left for anything
downstream to remember. The tail is padded to the video's exact length, because
two lanes of different lengths make the timeline ambiguous the moment anything
is cut from it.

## Nothing is remembered as an index

AVFoundation numbers devices in enumeration order, so the screens are numbered
after the cameras. With four cameras attached the small display was index 5;
unplug two and it is 3. An index in a config file is correct until the next
time something is plugged in, and then wrong *silently* — you find out by
recording the wrong display for twenty minutes.

So the screen is chosen by **measurement** — every screen is opened for one
frame and asked its size, and the smaller wins — and the microphone by
**name**. Both survive renumbering. `make devices` prints what it found:

```
Screens (AVFoundation video devices):
  [4] Capture screen 0   5120x1440
  [5] Capture screen 1   2560x1440   <- recorda picks this

Audio devices:
  [1] Scarlett Solo 4th Gen   <- recorda picks this
```

Measuring costs a second or two per display, so it is cached. `rescan` in the
page, or `make devices`, after plugging one in.

## Why it lives on the host and nowhere else

AVFoundation needs a window server, a logged-in session and CoreAudio. A
container has none of the three, so this cannot be built, run or tested in the
devbox — `scripts/start.sh` refuses to start in one rather than failing later
inside ffmpeg with an I/O error that reads like a broken device.

That is the same shape as `prober`, for a different reason: prober cannot leave
the host because no sandbox holds the age key, recorda because no sandbox holds
a screen.

It also needs **Screen Recording permission** for whatever runs it — the
terminal, or your editor. Without it every screen probes as an error and the
Record button stays disabled. System Settings › Privacy & Security › Screen
Recording.

## A recording is a directory

```
recordings/2026-08-29-2013-07/
  video.mp4    picture only, stream-copied out of the capture
  audio.wav    the microphone, lossless, aligned to the picture
  peaks.json   what the waveform lane draws
  meta.edn     everything else
  ffmpeg.log   what the capture said while it ran
```

No SQLite, unlike every sibling in this workspace. There is one user, the rows
are files, and every query this app has is `ls`. A database would add a schema
to migrate and a second place for the truth to live — and when the two
disagreed, the files would still be the ones holding the video.

The capture is deleted once it has been split, because everything in it now
lives in those two files, one by stream copy and one losslessly.
`:keep-capture? true` to keep it, at roughly double the disk.

**Budget about 45 MB a minute at 1440p.** `recordings/` is gitignored for that
reason and not as a matter of taste.

## Playing two files as one thing

**The audio clock is the master, and the audio is never touched.** The take is
decoded once into an `AudioBuffer` and played by an `AudioBufferSourceNode`,
which starts at a scheduled instant and plays the samples exactly as recorded.
It cannot stall, cannot be seeked mid-flight, and is never rate-trimmed. The
playhead is not read off any element — it is computed:

    position = offset-when-we-started + (ctx.currentTime - ctx-time-we-started-at)

The **picture** is what gets corrected, because correcting it is free: its rate
can be bent 8% with nothing visible, and it can be seeked outright for the cost
of one frame. The equivalent moves on the audio side are a warble and a click.

Two earlier versions are worth knowing about, because both are the obvious
thing and both are audibly wrong. Slaving the audio by **seeking** it feeds
itself — a seek flushes the decoder, the flush stalls it, the stall creates the
drift that trips the next seek; measured, that fired 33 times in seven seconds.
Slaving it by **trimming playbackRate** removes the flushes but runs a
time-stretcher over speech for as long as the rate is held off 1.0, and it is
never off it for long because the system clock and the audio clock genuinely
drift. HTML once had `MediaController` for this job and it was withdrawn from
the spec, the stated reason being that slaved elements "are not actually kept
in sync".

**Nothing moves until the audio clock does.** An `AudioContext` does not start
its clock when you tell it to play — it starts when the output device is ready,
and on a cold context that was over a second here. Following `playing?` rather
than "is it actually sounding" put the picture in motion during that window,
where it ran ahead and was hard-seeked backwards over and over. Measured after
the fix: zero backward steps, video rate pinned at 1.000 for a whole take, and
80–107 ms from click to motion once the context is warm.

`space` plays and pauses, the arrows jump five seconds, and clicking either
lane seeks.

The cost of decoding up front is memory: roughly 11 MB per minute of take
(mono float32 at 48 kHz). Fine for the screencasts this is for; the thing to
change first if it ever is not.

## Ports

`3200` for the app, `9808` for shadow, `7902` for nREPL — the next free ones
after the suite, prober on 3180 and remote-files-organizer on 3190. Declared in
`config.edn` and `shadow-cljs.edn`, which is the single source; `config.edn`
itself is gitignored and made from `config.edn.template` on first start.

## Editing, when it comes

The shape is laid for it and none of it is built. `meta.edn` carries an
`:edits []` that nothing reads yet, and the intent is that it stays a
**non-destructive** list — cuts and gain moves described, never applied to the
files, and replayed through ffmpeg only on export. The captured take stays the
captured take.

The two things to build first are a filmstrip in the video lane, which is
`ffmpeg -i video.mp4 -vf fps=1/2,scale=160:-1`, and multi-resolution peaks,
because 8000 buckets is an overview and not a zoom.
