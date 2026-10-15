(ns dk.simongray.podcast.format.image.impl-test
  "The internals of format.image: the colours that the most pixels have."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            [clojure.test :refer [deftest is]]
            [dk.simongray.podcast.format.image.impl :as image.impl]))

(deftest the-colours-of-the-most-pixels
  (is (= ["#2244a2" "#c03020"] (image.impl/palette-of (concat (repeat 6 [0x20 0x40 0xa0 255]) (repeat 6 [0x24 0x48 0xa4 255])
                                                              (repeat 5 [0xc0 0x30 0x20 255]))
                                                      5))
      "the average of the shades that count together, the most common first")
  (is (= ["#c03020"] (image.impl/palette-of (concat (repeat 6 [0x20 0x40 0xa0 0]) (repeat 5 [0xc0 0x30 0x20 255])) 5))
      "the transparent pixels left out")
  (is (= [] (image.impl/palette-of [[0 0 0 0]] 5)))
  (is (= ["#000000"] (image.impl/palette-of [[0 0 0 255] [255 255 255 255]] 1)) "a tie settled the same way on each platform"))
