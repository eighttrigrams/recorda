(ns et.rec.redact-test
  "The parts of the redaction with a judgement in them.

   The OCR is not tested here — it is a framework doing what it does, and a
   test of it would be a test of macOS. What is worth pinning down is
   everything downstream of it: whether a misread still matches, whether a box
   covers the whole word, whether two sightings of the same thing become one
   box or two, and whether a stale scan is refused. Those are decisions this
   namespace makes, and every one of them leans the same way — over-blur is
   cheap, under-blur is the whole failure."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [et.rec.redact :as r]))

(defn- spans->strings [text term]
  (map (fn [[s e]] (subs text s e))
       (r/fuzzy-spans text term (r/tolerance term))))

(deftest tolerance-is-length-proportional-and-zero-for-short-terms
  (testing "short terms must match exactly, or they match half the screen"
    (is (= 0 (r/tolerance "acme")))
    (is (= 0 (r/tolerance "abcdef"))))
  (testing "one edit per seven characters, capped"
    (is (= 1 (r/tolerance "sk-ant-x")))
    (is (= 3 (r/tolerance "dan@eighttrigrams.net")))
    (is (= 4 (r/tolerance (apply str (repeat 100 "x")))))))

(deftest finds-the-term-it-was-given
  (let [text "removed dan@eighttrigrams.net from the subscriber list"]
    (testing "exactly, and without running into the next word"
      (is (= ["dan@eighttrigrams.net"] (spans->strings text "dan@eighttrigrams.net"))))
    (testing "as a fragment of a longer word"
      (is (= ["eighttrigrams.net"] (spans->strings text "eighttrigrams.net"))))
    (testing "and not when it is absent"
      (is (empty? (spans->strings "nothing of the sort here" "dan@eighttrigrams.net"))))
    (testing "every time it appears"
      (let [t "a dan@x.net b dan@x.net c"]
        (is (= ["dan@x.net" "dan@x.net"] (spans->strings t "dan@x.net")))))))

(deftest survives-the-misread-that-motivated-all-of-this
  ;; tesseract read this frame's small grey address as `dang@eighttrigrams.net`.
  ;; Vision read it correctly, but the next engine or the next font will not,
  ;; and an exact match against a misread is a leak that looks like a clean
  ;; export.
  (let [text "removed dang@eighttrigrams.net from the list"]
    (is (= ["dang@eighttrigrams.net"] (spans->strings text "dan@eighttrigrams.net"))))
  (testing "a dropped character too"
    (is (= ["dan@eighttrigams.net"]
           (spans->strings "see dan@eighttrigams.net now" "dan@eighttrigrams.net"))))
  (testing "but not a different address of similar shape"
    (is (empty? (spans->strings "write to bob@othercompany.org" "dan@eighttrigrams.net")))))

(defn- word [line x y w h text]
  {:line line :x x :y y :w w :h h :conf 1.0 :text text})

(deftest a-box-covers-whole-words
  (testing "asking for part of a word blurs the word — more than asked, which
            is the side to be wrong on, and it avoids trusting a character box
            on a proportional font"
    (let [words [(word 0 10 100 60 20 "removed")
                 (word 0 80 100 190 20 "dan@eighttrigrams.net")
                 (word 0 280 100 40 20 "from")]
          hits  (r/line-hits words ["eighttrigrams"])]
      (is (= 1 (count hits)))
      (is (= {:x 80 :y 100 :w 190 :h 20} (select-keys (first hits) [:x :y :w :h])))))

  (testing "a term of several words is one box over all of them"
    (let [words [(word 0 10 50 40 16 "the")
                 (word 0 60 50 70 16 "Docker")
                 (word 0 140 50 60 16 "Scout")
                 (word 0 210 50 40 16 "page")]
          hits  (r/line-hits words ["docker scout"])]
      (is (= 1 (count hits)))
      (is (= {:x 60 :y 50 :w 140 :h 16} (select-keys (first hits) [:x :y :w :h])))))

  (testing "case does not matter — `line-hits` folds both sides, and folds them
            a character at a time, because a fold that changes the length would
            slide every offset in this namespace off its box"
    (let [words [(word 0 10 100 60 20 "Removed")
                 (word 0 80 100 190 20 "Dan@EightTrigrams.net")]]
      (is (= 1 (count (r/line-hits words ["DAN@eighttrigrams.NET"]))))))

  (testing "a term straddling two lines is not welded across them"
    (let [words [(word 0 10 50 70 16 "Docker")
                 (word 1 10 80 60 16 "Scout")]]
      (is (empty? (r/frame-hits words ["docker scout"]))))))

(deftest sightings-become-boxes-that-live-for-a-while
  (let [opts {:fps 2.0}
        at   (fn [t x] {:t t :term "x" :x x :y 100 :w 50 :h 20})]

    (testing "a term sitting still is one box, not one per sample"
      (let [ts (r/tracks (map #(at (* 0.5 %) 200) (range 10)) opts)]
        (is (= 1 (count ts)))
        (is (= [0.0 4.5] [(:t0 (first ts)) (:t1 (first ts))]))
        (is (= 10 (:n (first ts))))))

    (testing "a term that jumps elsewhere opens its own box rather than
              stretching one across everything in between"
      (let [ts (r/tracks [(at 0.0 200) (at 0.5 200) (at 1.0 1800) (at 1.5 1800)] opts)]
        (is (= 2 (count ts)))
        (is (= #{200 1800} (set (map :x ts))))))

    (testing "a term that goes away and comes back leaves the middle alone"
      (let [ts (r/tracks [(at 0.0 200) (at 0.5 200) (at 9.0 200) (at 9.5 200)] opts)]
        (is (= 2 (count ts)))))

    (testing "one missed sample does not split a box, because a sample missed
              is not a term gone"
      (let [ts (r/tracks [(at 0.0 200) (at 1.0 200)] opts)]
        (is (= 1 (count ts)))))

    (testing "a slow drift cannot creep a box across the screen"
      (let [ts (r/tracks (map #(at (* 0.5 %) (* 40 %)) (range 12)) opts)]
        (is (< 1 (count ts)) "the area guard has to break the chain somewhere")
        (is (every? #(<= (:w %) 300) ts))))

    (testing "two different terms in the same place stay two boxes, so removing
              one term does not silently uncover the other"
      (let [ts (r/tracks [{:t 0.0 :term "a" :x 10 :y 10 :w 50 :h 20}
                          {:t 0.0 :term "b" :x 10 :y 10 :w 50 :h 20}]
                         opts)]
        (is (= 2 (count ts)))))))

(deftest a-finished-box-is-padded-held-and-even
  (let [b (r/finish {:x 100 :y 200 :w 190 :h 20 :t0 5.0 :t1 8.0 :term "x" :n 6}
                    {:fps 2.0 :hold 0.4} 2560 1440)]
    (testing "padded beyond the ink, because a blur that hugs the letters
              leaves their shape"
      (is (< (:x b) 100))
      (is (> (+ (:x b) (:w b)) 290)))
    (testing "held either side, to cover the samples nobody looked at"
      (is (= 4.6 (:t0 b)))
      (is (= 8.9 (:t1 b))))
    (testing "even in both dimensions and both offsets, or 4:2:0 has no chroma
              plane to put it on"
      (is (every? even? [(:x b) (:y b) (:w b) (:h b)]))))

  (testing "clamped to the frame, and never held before it starts"
    (let [b (r/finish {:x 4 :y 2 :w 40 :h 30 :t0 0.1 :t1 0.6 :term "x" :n 2}
                      {:fps 2.0 :hold 0.4} 200 100)]
      (is (= 0.0 (:t0 b)))
      (is (<= 0 (:x b)))
      (is (<= (+ (:x b) (:w b)) 200))
      (is (<= (+ (:y b) (:h b)) 100)))))

(deftest the-filter-says-what-it-does
  (let [boxes [{:x 10 :y 20 :w 100 :h 40 :t0 1.0 :t1 2.0}
               {:x 50 :y 60 :w 80 :h 30 :t0 3.0 :t1 4.0}]]

    (testing "nothing to do at all is no filter, which is what keeps the
              ordinary export a stream copy"
      (is (nil? (r/video-filter [] {:style :blur} nil))))

    (testing "a crop alone is still just a crop"
      (is (= "[0:v]crop=8:6:2:4[vout]"
             (r/video-filter [] {:style :blur} {:x 2 :y 4 :w 8 :h 6}))))

    (testing "each box is cropped out, blurred on its own and put back, only
              for as long as it was seen"
      (let [f (r/video-filter boxes {:style :blur} nil)]
        (is (str/includes? f "split=3"))
        (is (str/includes? f "crop=100:40:10:20"))
        (is (str/includes? f "overlay=10:20:enable='between(t,1.000,2.000)'"))
        (is (str/includes? f "overlay=50:60:enable='between(t,3.000,4.000)'"))
        (is (str/ends-with? f "[vout]"))))

    (testing "the crop comes after the blurring, so the boxes stay in the
              coordinates they were found in"
      (let [f (r/video-filter boxes {:style :blur} {:x 0 :y 0 :w 640 :h 480})]
        (is (< (str/index-of f "overlay=") (str/index-of f "crop=640:480")))))

    (testing "a solid box needs no second copy of the picture"
      (let [f (r/video-filter boxes {:style :box} nil)]
        (is (not (str/includes? f "split")))
        (is (str/includes? f "drawbox=x=10:y=20:w=100:h=40:color=black@1.0:t=fill"))))))

(deftest a-scan-is-only-valid-for-what-it-was-made-from
  (let [k #(r/scan-key %)]
    (testing "the terms it was made for"
      (is (not= (k {:redact {:terms ["a"]}}) (k {:redact {:terms ["a" "b"]}}))))
    (testing "the order they were typed in is not part of it"
      (is (= (k {:redact {:terms ["a" "b"]}}) (k {:redact {:terms ["b" "a"]}}))))
    (testing "how densely it looked"
      (is (not= (k {:redact {:terms ["a"] :fps 2}})
                (k {:redact {:terms ["a"] :fps 4}}))))
    (testing "and which assembly, because an edit moves every time in the box
              list to a different moment"
      (is (not= (k {:rev 3 :redact {:terms ["a"]}})
                (k {:rev 4 :redact {:terms ["a"]}}))))))
