(ns dk.simongray.podcast.app.sync-encrypted-test
  "Documents encrypted at rest: the envelope and its round trip, a wrong
  passphrase, plain documents passing through, an envelope sealed on the
  other platform opening here, and two devices kept in step through an
  encrypting store."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            #?@(:clj [[dk.simongray.podcast.app.player :as player]
                      [dk.simongray.podcast.app.sync :as sync]])
            [clojure.string :as str]
            [clojure.test :refer [deftest is #?@(:clj [testing]) #?(:cljs async)]]
            [dk.simongray.podcast.app.sync.encrypted :as encrypted]
            [dk.simongray.podcast.app.sync.store :as store]
            [dk.simongray.podcast.test-support :refer [fixture #?@(:clj [t0 t1 t2 t3 a])]]))

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
  (is (nil? (encrypted/envelope nil))))

#?(:clj
   (deftest sealing-and-opening
     (let [sealed (encrypted/encrypt passphrase plaintext {:iterations 1000})]
       (is (encrypted/envelope sealed))
       (is (= plaintext (encrypted/decrypt passphrase sealed)))
       (is (not= sealed (encrypted/encrypt passphrase plaintext {:iterations 1000})) "a fresh salt and IV every time")
       (is (= plaintext (encrypted/decrypt "whatever" plaintext)) "a plain document passes through")
       (is (thrown-with-msg? Exception #"does not open" (encrypted/decrypt "wrong" sealed)))
       (is (= ::encrypted/locked (:type (ex-data (try (encrypted/decrypt "wrong" sealed) (catch Exception e e))))))
       (is (= in-the-jvm-envelope (encrypted/decrypt passphrase sealed-by-the-jvm)))
       (is (= in-the-webcrypto-envelope (encrypted/decrypt passphrase sealed-by-webcrypto))
           "an envelope WebCrypto sealed opens on the JVM"))))

#?(:clj
   (deftest an-encrypting-store
     (let [documents (atom {})
           s         (encrypted/store (store/memory documents)
                                      {:passphrase passphrase :iterations 1000 :except #{store/opml-name}})]
       ((:put s) "phone.edn" plaintext {})
       ((:put s) store/opml-name "<opml/>" {})
       (is (str/starts-with? (:body (@documents "phone.edn")) "{:encrypted") "ciphertext at rest")
       (is (= "<opml/>" (:body (@documents store/opml-name))) "except what is excepted")
       (is (= {:body plaintext :version "1"} ((:fetch s) "phone.edn")) "plaintext on the way out, the version as it is")
       (is (= [{:name "phone.edn" :version "1"} {:name store/opml-name :version "1"}] ((:list s))))
       (is (= {:conflict? true} ((:put s) "phone.edn" plaintext {:if-version "stale"})) "the preconditions pass through")
       (is (nil? ((:fetch s) "missing.edn")))
       (testing "a plain document already there reads as it is"
         ((:put (store/memory documents)) "old.edn" plaintext {})
         (is (= plaintext (:body ((:fetch s) "old.edn")))))
       (testing "the wrong passphrase names the document"
         (let [other (encrypted/store (store/memory documents) {:passphrase "wrong" :iterations 1000})]
           (is (thrown-with-msg? Exception #"does not open phone.edn" ((:fetch other) "phone.edn")))))
       (testing "a passphrase may be a function, for one kept in a keychain"
         (let [asked (atom 0)
               s2    (encrypted/store (store/memory documents) {:passphrase (fn [] (swap! asked inc) passphrase) :iterations 1000})]
           (is (= plaintext (:body ((:fetch s2) "phone.edn"))))
           (is (= plaintext (:body ((:fetch s2) "phone.edn"))))
           (is (= 1 @asked) "and the key is derived once per salt")))
       (is (:ok? (store/check! s)) "the generic check passes through it"))))

#?(:clj
   (deftest two-devices-through-an-encrypting-store
     (let [documents (atom {})
           backend   (fn [pass]
                       (encrypted/backend (store/memory documents)
                                          {:passphrase pass :iterations 1000 :opml? true :except #{store/opml-name}}))
           phone     (sync/sync! (player/subscribe (player/state "phone") "feed-a" {:url "https://a/rss" :title "A"} t0)
                                 [(backend passphrase)] t1)
           laptop    (sync/sync! (player/progress (player/state "laptop") a 60 3600 t1) [(backend passphrase)] t2)
           phone2    (sync/sync! (:state phone) [(backend passphrase)] t3 {:ctx (:ctx phone)})]
       (is (nil? (:failed phone)))
       (is (nil? (:failed laptop)))
       (is (nil? (:failed phone2)))
       (is (= 60 (:position (player/episode (:state phone2) a))))
       (is (every? #(str/starts-with? (:body %) "{:encrypted") (vals (dissoc @documents store/opml-name)))
           "every document at rest is ciphertext")
       (is (str/includes? (:body (@documents store/opml-name)) "https://a/rss") "the subscription list stays readable, as asked")
       (let [{:keys [failed]} (sync/sync! (player/state "tablet") [(backend "wrong")] t3)]
         (is (str/includes? (get failed "memory") "does not open"))))))

#?(:cljs
   (deftest sealing-and-opening-with-webcrypto
     (async done
       (let [documents (atom {})
             s         (encrypted/store (store/memory documents) {:passphrase passphrase :iterations 1000})]
         (-> (encrypted/encrypt passphrase plaintext {:iterations 1000})
             (.then (fn [sealed]
                      (is (encrypted/envelope sealed))
                      (encrypted/decrypt passphrase sealed)))
             (.then (fn [opened]
                      (is (= plaintext opened))
                      (encrypted/decrypt passphrase sealed-by-the-jvm)))
             (.then (fn [opened]
                      (is (= in-the-jvm-envelope opened) "an envelope the JVM sealed opens with WebCrypto")
                      (encrypted/decrypt passphrase sealed-by-webcrypto)))
             (.then (fn [opened]
                      (is (= in-the-webcrypto-envelope opened))
                      (-> (encrypted/decrypt "wrong" sealed-by-the-jvm)
                          (.then (fn [_] (is false "the wrong passphrase opened it")))
                          (.catch (fn [e] (is (str/includes? (ex-message e) "does not open")))))))
             (.then (fn [_] ((:put s) "phone.edn" plaintext {})))
             (.then (fn [_]
                      (is (str/starts-with? (:body (@documents "phone.edn")) "{:encrypted") "ciphertext at rest")
                      ((:fetch s) "phone.edn")))
             (.then (fn [doc]
                      (is (= {:body plaintext :version "1"} doc))
                      (store/check! s)))
             (.then (fn [{:keys [ok? checks]}]
                      (is ok? (pr-str checks))))
             (.catch (fn [e] (is false (str "rejected: " e))))
             (.finally done))))))
