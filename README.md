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

## The second that is not there

The screen starts delivering frames immediately. The interface takes about a
second to wake up. Measured on this machine, across four takes:

```
0.944 s    1.031 s    1.404 s    0.043 s
```

The last one is low because the Scarlett was still warm from the take before
it. **The gap is real, it is different every time, and it cannot be a
constant.** Anything that hardcodes it is right once.

This is also why the capture is a `.mkv` and not an `.mp4`. Matroska records
the gap faithfully, as the audio stream's `start_time`. Hand the same two
streams to the MP4 muxer and it is discarded without a word — which puts your
voice a second ahead of the picture, in a file that looks fine.

So: capture to Matroska, read the gap back out with ffprobe, then write it into
`audio.wav` as **real silence** at the head. After that the WAV's first sample
is the video's first frame and there is nothing left to remember. The tail is
padded the same way, to the video's exact length, so the two lanes are the same
length and a cut at the end means one thing rather than two.

The number is kept in `meta.edn` and shown under the player, because a take
where the mic came up 1.4 s late is a take whose first breath is missing.

## Your Scarlett has four channels

The Solo 4th gen presents **four** to CoreAudio — the XLR mic, the instrument
input, and a loopback pair the 4th gen added. Record it as it comes and you get
a four channel file, three of whose channels are silence and whatever the
desktop was playing.

recorda takes channel 0, the microphone, and writes mono. `:mic-channel` in
`config.edn` if that is ever wrong.

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

Two media elements will not stay together on their own, so the video plays
**muted and authoritative** and the audio follows it, pulled back whenever it
has slipped more than 80 ms. Tighter and the correction is itself audible as a
stutter; looser and speech visibly lags the pointer it belongs to.

`space` plays and pauses, the arrows jump five seconds, and clicking either
lane seeks.

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
