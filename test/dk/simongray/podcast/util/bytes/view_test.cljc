(ns dk.simongray.podcast.util.bytes.view-test
  "Views of files whose bytes come in parts: the numbers that they read, the
  ranges that they lack, a read past the end of the file, and a slice
  across parts, on both platforms."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast.util.bytes.view :as view]
            [dk.simongray.webcrypto.bytes :as bytes]))

(deftest a-view-of-parts
  (let [v (view/add (view/view 100) 10 (bytes/of [1 2 3 4 5 6 7 8]) 100)]
    (is (= 0x01020304 (view/u32 v 10)))
    (is (= 0x0102030405060708 (view/u64 v 10)))
    (is (= [[18 30]] (view/missing (try (view/need v 12 30) (catch #?(:clj Exception :cljs :default) e e)))))
    (is (= [[0 10] [18 20]] (view/missing (try (view/need-all v [[0 12] [15 20]]) (catch #?(:clj Exception :cljs :default) e e))))
        "every range it lacks, at once")
    (is (view/truncated? (try (view/need v 100 104) (catch #?(:clj Exception :cljs :default) e e))))
    (is (nil? (view/need (view/add v 90 (bytes/of (range 10)) 100) 95 104)) "the end cut at the end of the file")
    (is (= [[0 20]] (map (fn [[o b]] [o (bytes/length b)]) (:parts (view/add v 0 (bytes/of (range 20)) 100))))
        "a part in place of those it covers, as a server that ignores ranges sends the start again")
    (is (= [0 8 18] [(view/held v) (view/held (view/whole (bytes/of (range 8)))) (view/held (view/add v 0 (bytes/of (range 10)) 100))])
        "the bytes from the start without a gap"))
  (testing "a slice across the parts of a view"
    (let [v       (-> (view/view 16) (view/add 0 (bytes/of (range 8)) 16) (view/add 8 (bytes/of (range 8 16)) 16))
          numbers (fn [b] (map #(bytes/at b %) (range (bytes/length b))))]
      (is (= (range 5 11) (numbers (view/slice v 5 11))))
      (is (view/truncated? (try (view/slice v 10 20) (catch #?(:clj Exception :cljs :default) e e)))
          "past the end of the file"))))

(deftest text-in-its-encodings
  (let [text (fn [xs encoding] (view/text (view/whole (bytes/of xs)) 0 (count xs) encoding))]
    (is (= "Ærø" (text [0xC6 0x72 0xF8] :latin1)))
    (is (= "Ærø" (text [0xC3 0x86 0x72 0xC3 0xB8] :utf8)))
    (is (= "Ærø" (text [0x00 0xC6 0x00 0x72 0x00 0xF8] :utf16be)))
    (is (= "Ærø" (text [0xC6 0x00 0x72 0x00 0xF8 0x00] :utf16le))))
  (is (view/utf8? (view/whole (bytes/encode "Ærø")) 0 5))
  (is (not (view/utf8? (view/whole (bytes/of [0xC6 0x72 0xF8])) 0 3)))
  (is (not (view/utf8? (view/whole (bytes/encode "plain")) 0 5)) "ASCII says nothing about the encoding"))
