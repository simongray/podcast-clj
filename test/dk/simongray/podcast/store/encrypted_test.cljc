(ns dk.simongray.podcast.store.encrypted-test
  "Documents encrypted at rest: the envelope and its round trip, a wrong
  passphrase, plain documents passing through, an envelope sealed on the
  other platform opening here, and two devices kept in step through an
  encrypting store."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            #?(:clj [dk.simongray.podcast.sync :as sync])
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing #?(:cljs async)]]
            [dk.simongray.podcast.store :as store]
            [dk.simongray.podcast.store.encrypted :as encrypted]
            [dk.simongray.podcast.test-support :refer [fixture #?@(:clj [t0 t1 t2 t3 failed note text
                                                                          with-notes])]]))

(def passphrase
  "correct horse battery staple")

(def plaintext
  "{:version 1 :device \"phone\" :state {}}")

(def sealed-by-the-jvm
  (fixture "encrypted/jvm.edn"))

(def sealed-by-webcrypto
  (fixture "encrypted/webcrypto.edn"))

(def in-the-jvm-envelope
  "{:version 1 :device \"phone\" :written-at #inst \"2026-09-26T20:00:00.000-00:00\" :state {:device \"phone\" :subscriptions {} :episodes {} :queue {}}}")

(def in-the-webcrypto-envelope
  "{:version 1 :device \"laptop\" :written-at #inst \"2026-09-26T20:00:00.000-00:00\" :state {:device \"laptop\" :subscriptions {} :episodes {} :queue {}}}")

(deftest the-envelope
  (doseq [sealed [sealed-by-the-jvm sealed-by-webcrypto]]
    (let [env (encrypted/envelope sealed)]
      (is (= 1 (:encrypted env)))
      (is (= "AES-256-GCM" (:cipher env)))
      (is (= "PBKDF2-HMAC-SHA256" (:kdf env)))
      (is (= 100000 (:iterations env)))
      (is (every? string? ((juxt :salt :iv :data) env)))))
  (is (nil? (encrypted/envelope plaintext)) "a plain document is not one")
  (is (nil? (encrypted/envelope "{:encrypted 1}")) "nor a map without its parts")
  (is (nil? (encrypted/envelope nil)))
  (testing "nor one asking for an iteration count that would hold the device up, or none at all"
    (doseq [n [0 -1 "\"x\"" "nil" (inc encrypted/max-iterations)]]
      (is (nil? (encrypted/envelope (str/replace sealed-by-the-jvm #":iterations \d+" (str ":iterations " n)))) n)))
  (testing "nor one whose parts are not base64"
    (doseq [iv ["\"\"" "\"not base64!\"" "\"AAA\"" "1"]]
      (is (nil? (encrypted/envelope (str/replace sealed-by-the-jvm #":iv \"[^\"]*\"" (str ":iv " iv)))) iv))))

(deftest a-store-needs-a-passphrase
  (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error) (encrypted/store (store/memory) nil))
      "also in a release build, which leaves assertions out"))

#?(:clj
   (deftest sealing-and-opening
     (let [sealed (encrypted/encrypt plaintext passphrase {:iterations 1000})]
       (is (encrypted/envelope sealed))
       (is (= plaintext (encrypted/decrypt sealed passphrase)))
       (is (not= sealed (encrypted/encrypt plaintext passphrase {:iterations 1000})) "a fresh salt and IV every time")
       (is (= plaintext (encrypted/decrypt plaintext "whatever")) "a plain document passes through")
       (is (thrown-with-msg? Exception #"does not open" (encrypted/decrypt sealed "wrong")))
       (is (= ::encrypted/locked (:type (ex-data (try (encrypted/decrypt sealed "wrong") (catch Exception e e))))))
       (is (= in-the-jvm-envelope (encrypted/decrypt sealed-by-the-jvm passphrase)))
       (is (= in-the-webcrypto-envelope (encrypted/decrypt sealed-by-webcrypto passphrase))
           "an envelope WebCrypto sealed opens on the JVM"))))

#?(:clj
   (deftest an-encrypting-store
     (let [documents (atom {})
           s         (encrypted/store (store/memory documents)
                                      passphrase
                                      {:iterations 1000 :except #{sync/opml-name}})]
       ((:put s) "phone.edn" plaintext {})
       ((:put s) sync/opml-name "<opml/>" {})
       (is (str/starts-with? (:body (@documents "phone.edn")) "{:encrypted") "ciphertext at rest")
       (is (= "<opml/>" (:body (@documents sync/opml-name))) "except what is excepted")
       (is (= {:body plaintext :version "1"} ((:fetch s) "phone.edn" {})) "plaintext on the way out, the version as it is")
       (is (= [{:name "phone.edn" :version "1"} {:name sync/opml-name :version "1"}] ((:list s))))
       (is (= {:conflict? true} ((:put s) "phone.edn" plaintext {:if-version "stale"})) "the preconditions pass through")
       (is (nil? ((:fetch s) "missing.edn" {})))
       (testing "a body of bytes, sealed as bytes"
         ((:put s) "a.ciff" (byte-array [0 -1 1]) {})
         (is (str/starts-with? (:body (@documents "a.ciff")) "{:encrypted"))
         (is (= [0 -1 1] (vec (:body ((:fetch s) "a.ciff" {:as :bytes})))))
         (is (= plaintext (String. ^bytes (:body ((:fetch s) "phone.edn" {:as :bytes}))
                                   "UTF-8"))
             "and text as bytes"))
       (testing "a plain document already there reads as it is"
         ((:put (store/memory documents)) "old.edn" plaintext {})
         (is (= plaintext (:body ((:fetch s) "old.edn" {})))))
       (testing "the wrong passphrase names the document"
         (let [other (encrypted/store (store/memory documents) "wrong" {:iterations 1000})]
           (is (thrown-with-msg? Exception #"does not open phone.edn" ((:fetch other) "phone.edn" {})))))
       (testing "a passphrase may be a function, for one kept in a keychain"
         (let [asked (atom 0)
               s2    (encrypted/store (store/memory documents) (fn [] (swap! asked inc) passphrase) {:iterations 1000})]
           (is (= plaintext (:body ((:fetch s2) "phone.edn" {}))))
           (is (= plaintext (:body ((:fetch s2) "phone.edn" {}))))
           (is (= 1 @asked) "and the key is derived once per salt")))
       (testing "and one corrected later is asked for again when the cached key opens nothing"
         (let [typed (atom "typo")
               s3    (encrypted/store (store/memory documents) #(deref typed) {:iterations 1000})]
           (is (thrown? Exception ((:fetch s3) "phone.edn" {})))
           (reset! typed passphrase)
           (is (= plaintext (:body ((:fetch s3) "phone.edn" {}))))))
       (testing "a store without a passphrase isn't made"
         (is (thrown? clojure.lang.ExceptionInfo (encrypted/store (store/memory documents) nil))))
       (is (:ok? (store/check! s)) "the generic check passes through it"))))

#?(:clj
   (deftest two-devices-through-an-encrypting-store
     (let [documents (atom {})
           summary   (fn [state] {"summary.txt" (str (count (:notes state)) " notes")})
           backend   (fn [pass]
                       (sync/backend (encrypted/store (store/memory documents)
                                                       pass
                                                       {:iterations 1000 :except #{"summary.txt"}})
                                      (assoc with-notes :extra-documents-fn summary)))
           phone     (sync/sync! (note {:device "phone"} "milk" "Buy milk" t0) [(backend passphrase)] (assoc with-notes :at t1))
           laptop    (sync/sync! (note {:device "laptop"} "milk" "Buy oat milk" t1) [(backend passphrase)] (assoc with-notes :at t2))
           phone2    (sync/sync! (:state phone) [(backend passphrase)] (assoc with-notes :at t3 :cursors (:cursors phone)))]
       (is (nil? (failed phone)))
       (is (nil? (failed laptop)))
       (is (nil? (failed phone2)))
       (is (= "Buy oat milk" (text (:state phone2) "milk")))
       (is (every? #(str/starts-with? (:body %) "{:encrypted") (vals (dissoc @documents "summary.txt")))
           "every document at rest is ciphertext")
       (is (= "1 notes" (:body (@documents "summary.txt"))) "a document left in the clear stays readable, as asked")
       (let [step (sync/sync! {:device "tablet"} [(backend "wrong")] (assoc with-notes :at t3))]
         (is (str/includes? (get (failed step) "memory") "does not open"))))))

#?(:cljs
   (deftest sealing-and-opening-with-webcrypto
     (async done
       (let [documents (atom {})
             s         (encrypted/store (store/memory documents) passphrase {:iterations 1000})]
         (-> (encrypted/encrypt plaintext passphrase {:iterations 1000})
             (.then (fn [sealed]
                      (is (encrypted/envelope sealed))
                      (encrypted/decrypt sealed passphrase)))
             (.then (fn [opened]
                      (is (= plaintext opened))
                      (encrypted/decrypt sealed-by-the-jvm passphrase)))
             (.then (fn [opened]
                      (is (= in-the-jvm-envelope opened) "an envelope the JVM sealed opens with WebCrypto")
                      (encrypted/decrypt sealed-by-webcrypto passphrase)))
             (.then (fn [opened]
                      (is (= in-the-webcrypto-envelope opened))
                      (-> (encrypted/decrypt sealed-by-the-jvm "wrong")
                          (.then (fn [_] (is false "the wrong passphrase opened it")))
                          (.catch (fn [e] (is (str/includes? (ex-message e) "does not open")))))))
             (.then (fn [_] ((:put s) "phone.edn" plaintext {})))
             (.then (fn [_]
                      (is (str/starts-with? (:body (@documents "phone.edn")) "{:encrypted") "ciphertext at rest")
                      ((:fetch s) "phone.edn" {})))
             (.then (fn [doc]
                      (is (= {:body plaintext :version "1"} doc))
                      (store/check! s)))
             (.then (fn [{:keys [ok? checks]}]
                      (is ok? (pr-str checks))))
             (.catch (fn [e] (is false (str "rejected: " e))))
             (.finally done))))))
