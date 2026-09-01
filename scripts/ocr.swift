// Reads image paths on stdin, one per line. Writes what Vision found on stdout.
//
// Why a Swift file in a Clojure repo: the app is macOS-only already — it needs
// AVFoundation for the screen and CoreAudio for the interface, which is why it
// cannot run in the devbox. Vision is the same kind of thing, sitting in the
// same frameworks, and it reads a screenshot far better than the alternative.
// Measured on a real 2560x1440 frame of a screencast: Vision 0.24 s and 253
// words, tesseract 0.67 s and 171 words — and on the one term that mattered,
// an address in 12px grey, tesseract read `dang@eighttrigrams.net` where
// Vision read it correctly. A redaction that matches on a corrupted string is
// a redaction that does not happen.
//
// Paths come in on stdin rather than argv because a long take is thousands of
// frames and argv is not.
//
// Output, tab separated, one frame's rows following its `@`:
//
//   @  <path>
//   w  <line> <x> <y> <w> <h> <confidence> <text>
//
// A frame with no text still gets its `@`, so the caller can tell "nothing
// here" from "not looked at yet" — which is the difference between a clean
// frame and a silently skipped one.

import Foundation
import Vision
import AppKit

// Small text is the whole point: an address in a log line is 12px in a 1440p
// frame, which is 0.008 of the height. Vision's own default is 1/32, and at
// that setting the things worth redacting are exactly the things it skips.
let minHeight = Float(ProcessInfo.processInfo.environment["RECORDA_OCR_MIN_HEIGHT"] ?? "") ?? 0.008
let langs = (ProcessInfo.processInfo.environment["RECORDA_OCR_LANGS"] ?? "en-US")
    .split(separator: ",").map(String.init)

let out = FileHandle.standardOutput

func emit(_ s: String) {
    if let d = s.data(using: .utf8) { out.write(d) }
}

func scan(_ path: String) {
    emit("@\t\(path)\n")
    guard let img = NSImage(contentsOfFile: path),
          let cg = img.cgImage(forProposedRect: nil, context: nil, hints: nil) else {
        FileHandle.standardError.write("recorda-ocr: cannot read \(path)\n".data(using: .utf8)!)
        return
    }
    let W = CGFloat(cg.width), H = CGFloat(cg.height)

    let req = VNRecognizeTextRequest()
    req.recognitionLevel = .accurate
    // Off, deliberately. Language correction turns an API key into a word and
    // an address into prose — it improves reading and ruins matching.
    req.usesLanguageCorrection = false
    req.minimumTextHeight = minHeight
    req.recognitionLanguages = langs

    let handler = VNImageRequestHandler(cgImage: cg, options: [:])
    do { try handler.perform([req]) } catch {
        FileHandle.standardError.write("recorda-ocr: \(path): \(error)\n".data(using: .utf8)!)
        return
    }
    guard let obs = req.results else { return }

    for (li, o) in obs.enumerated() {
        guard let cand = o.topCandidates(1).first else { continue }
        let s = cand.string
        var cursor = s.startIndex
        for word in s.split(separator: " ", omittingEmptySubsequences: true) {
            guard let r = s.range(of: String(word), range: cursor..<s.endIndex) else { continue }
            cursor = r.upperBound
            // Vision can give a box for any character range of a line, which
            // is what makes a word the unit here rather than a whole line: a
            // line is often most of the screen's width, and blurring one to
            // hide an address in it would black out the sentence around it.
            guard let bb = try? cand.boundingBox(for: r) else { continue }
            let b = bb.boundingBox
            // Vision's origin is bottom-left and normalised; ffmpeg's is
            // top-left and in pixels.
            let x = b.minX * W
            let y = (1 - b.maxY) * H
            let w = b.width * W
            let h = b.height * H
            let text = word.replacingOccurrences(of: "\t", with: " ")
            emit("w\t\(li)\t\(Int(x.rounded(.down)))\t\(Int(y.rounded(.down)))\t\(Int(w.rounded(.up)))\t\(Int(h.rounded(.up)))\t\(cand.confidence)\t\(text)\n")
        }
    }
}

while let line = readLine(strippingNewline: true) {
    let p = line.trimmingCharacters(in: .whitespaces)
    if !p.isEmpty { scan(p) }
}
