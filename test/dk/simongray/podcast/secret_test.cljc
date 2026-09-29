(ns dk.simongray.podcast.secret-test
  "Texts that do not show when they are printed, on both platforms."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            #?(:clj [clojure.edn :as edn] :cljs [cljs.reader :as edn])
            #?(:clj [clojure.pprint :as pprint] :cljs [cljs.pprint :as pprint])
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast.secret :as secret]))

(def text "s3cr3t-71a8c14c")

(def token (secret/hide text))

(defn- shows?
  "Whether the text `s` has the secret, or a part of it."
  [s]
  (boolean (re-find #"s3cr3t|71a8" (str s))))

(deftest a-hidden-text-in-print
  (let [m {:user "ann" :token token :nested [{:token token}]}]
    (testing "each way to write a value"
      (doseq [[way written] {"pr-str"  (pr-str m)
                             "str"     (str m)
                             "str of the text" (str token)
                             "print"   (with-out-str (print m))
                             "prn"     (with-out-str (prn token))
                             "pprint"  (with-out-str (pprint/pprint m))
                             "ex-info" (pr-str (ex-info "failed" {:connection m}))
                             "the message of an exception" (ex-message (ex-info (str "failed for " token) {}))}]
        (is (not (shows? written)) way)
        (is (str/includes? written "#<secret>") way)))
    #?(:clj (is (= "#<secret>" (binding [*print-dup* true] (pr-str token)))))
    #?(:clj (is (= "#<secret>" (format "%s" token))))
    #?(:cljs (is (not (shows? (js/JSON.stringify (clj->js {:token token}))))))
    #?(:cljs (is (not (shows? (str "" token)))))
    (testing "a printed map does not come back without its secret: the reading fails"
      (is (thrown? #?(:clj Throwable :cljs js/Error) (edn/read-string (pr-str m)))))))

(deftest what-a-hidden-text-is
  (is (= text (secret/reveal token)))
  (is (= "plain" (secret/reveal "plain")) "a text that is not hidden stays as it is")
  (is (nil? (secret/reveal nil)))
  (is (nil? (secret/hide nil)))
  (is (secret/hidden? token))
  (is (not (secret/hidden? text)))
  (is (identical? token (secret/hide token)) "a hidden text stays as it is")
  (testing "two hidden texts are equal when their texts are"
    (is (= token (secret/hide text)))
    (is (= (hash token) (hash (secret/hide text))))
    (is (not= token (secret/hide "another")))
    (is (not= token text))
    (is (= {:secret token} {:secret (secret/hide text)}))
    (is (= 1 (count (set [token (secret/hide text)]))))))
