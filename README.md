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

## A project is a directory

A **project is one video**, built up over as many sittings as it takes. You make
a card, open it, and record into it: the first press is the opening, every later
press is appended. There is no global record button, because there is no such
thing as recording without somewhere for it to go.

```
recordings/2026-08-30-0052-05/
  segments/001/video.mp4   one sitting, exactly as captured, never modified
  segments/001/audio.wav
  segments/002/…           the next sitting, appended
  video.mp4                the assembly — segments concatenated
  audio.wav                the assembly
  peaks.json               what the waveform lane draws
  meta.edn                 everything else, including the segment list
  export.mp4               only after you export
```

No SQLite, unlike every sibling in this workspace. There is one user, the rows
are files, and every query this app has is `ls`.

## Trim, and record on

Park the playhead and press **Trim to playhead**: everything after it leaves the
video, and the next recording carries on from there. That is the whole editing
model, and it is enough for a screencast — talk, fumble a line, back up a bit,
carry on.

**A trim is a number, not a deletion.** The segment holding that instant gets an
`:out`; the ones after it are marked dropped; every file stays whole at its
original length. So `undo trim` puts all of it back, and nothing you recorded is
lost until you delete the project.

**None of this re-encodes.** The capture produces h264 with no B-frames and a
keyframe about every 0.4 s, which makes two operations exact by stream copy:
cutting a segment's tail, and joining segments end to end. So a project
assembles in about the time it takes to read and write its bytes, and no
generation of quality is lost however many times you trim it back and record on.

That asymmetry is *why* the model is append-and-trim rather than general
cutting. Cutting from the middle would need a keyframe at the resume point and
cutting from the start would snap to one; the tail does not.

Two things the assembly refuses rather than gets wrong. Stream-copy concat needs
identical stream parameters, so if the screen changes size between sittings it
says so instead of writing a file that plays wrong from the join onwards. And a
segment stays **pending** — holding its number, absent from the video — between
the moment recording starts and the moment its audio arrives, so a sitting that
fails leaves no hole.

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

## Cropping

**Crop area** lets you drag a box on the picture; the export is cropped to it.
**full frame** clears it.

The box is drawn **on the footage**, not on a preview of the screen, and it is
applied **on export**, not during capture. Both follow from the same choice.

Cropping during capture would be free — the frames are being encoded anyway —
and that is exactly why it is the wrong place. It would bake the decision in
before a single frame had been seen, and nothing could change it afterwards but
recording again. As an export setting the box stays a number: redraw it, clear
it, change your mind a week later. The same reason a trim is a number rather
than a deletion.

The price is that a cropped export re-encodes the video, where an uncropped one
stream-copies it. Only the export pays it, and the bitrate is scaled by how much
of the frame survives so a corner of the screen does not get a full-screen
budget.

Both dimensions are forced even. h264 in 4:2:0 stores chroma at half resolution
in each direction, so an odd width has no valid chroma plane — the encoder
either refuses or silently rounds, and silently is worse.

## Export

`Export mp4` in the player mixes the two tracks back into one file and hands it
to the browser's downloads, named after the take.

The video is **stream-copied** — its frames came off the hardware encoder during
the capture and are already h264 in an mp4, so a twenty minute export is a few
seconds of work and costs no quality. Only the audio is encoded, to AAC at
192k, which is transparent for a mono voice track and is what makes the result
a file other things will open. `audio.wav` stays lossless and is what an edit,
or a trip through a DAW, should start from.

**This is the only place the two tracks become one**, deliberately, so the pair
on disk stays the master and the mp4 stays a derivative you can throw away and
remake.

`export.mp4` is written beside the take and overwritten on each export, so a
take you have exported costs roughly twice the disk until you delete it — and
deleting the take takes it too. It is rebuilt on every request rather than
cached, because the moment edits exist its contents depend on them.

## Editing, when it comes

The shape is laid for it and none of it is built. `meta.edn` carries an
`:edits []` that nothing reads yet, and the intent is that it stays a
**non-destructive** list — cuts and gain moves described, never applied to the
files, and replayed through ffmpeg only on export. The captured take stays the
captured take.

Export is where that replay belongs, and it is already the shape that expects
it: `et.rec.export/edit-args` turns an edit list into ffmpeg arguments and
currently returns nothing. When it returns something, no caller changes.

The two things to build first are a filmstrip in the video lane, which is
`ffmpeg -i video.mp4 -vf fps=1/2,scale=160:-1`, and multi-resolution peaks,
because 8000 buckets is an overview and not a zoom.
