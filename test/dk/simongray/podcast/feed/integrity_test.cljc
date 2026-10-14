(ns dk.simongray.podcast.feed.integrity-test
  "The podcast:integrity of a file: the hashes of Subresource Integrity, a
  check of a file against them through the sender of the app, and the
  verdict on a file that doesn't come or that no one may fetch."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            [clojure.string :as str]
            [clojure.test :refer [deftest is #?(:cljs async)]]
            [dk.simongray.podcast.feed.integrity :as integrity]
            [dk.simongray.podcast.test-support :refer [ascii run #?@(:clj [local? with-file-server])]]
            [dk.simongray.wary-fetch.async :as async]
            [dk.simongray.webcrypto.bytes :as bytes]))

(deftest subresource-integrity
  (is (= {:algorithm "sha384" :digests #{"abc="}} (integrity/sri "sha256-xyz= sha384-abc=")) "the strongest wins")
  (is (= {:algorithm "sha512" :digests #{"a" "b"}} (integrity/sri "sha512-a sha512-b?ct=audio sha1-c")))
  (is (nil? (integrity/sri "md5-abc sha1-def")) "no hash SRI knows")
  (let [unverified [nil :file/unverifiable :info "A PGP signature needs the key of the publisher."]
        told       (juxt :valid? (comp :id :error) (comp :level :error) (comp :reason :error))
        result     (integrity/verify! "https://example.org/a.mp3" {:type "pgp-signature" :value "…"})]
    #?(:clj  (is (= unverified (told result)))
       :cljs (async done
               (-> result
                   (.then #(is (= unverified (told %)) "a promise, as for any other integrity"))
                   (.catch (fn [e] (is false (str "rejected: " e))))
                   (.finally done))))))

#?(:clj
   (deftest integrity-over-http
     (let [b      (bytes/of (range 256))
           digest (bytes/base64 (.digest (java.security.MessageDigest/getInstance "SHA-384") ^bytes b))]
       (with-file-server b {}
         (fn [url]
           (is (= {:valid? true :algorithm "sha384"}
                  (integrity/verify! url {:type "sri" :value (str "sha256-nope sha384-" digest)} {:allow-pred local?})))
           (is (= {:valid? false :algorithm "sha384"}
                  (integrity/verify! url {:type "sri" :value "sha384-AAAA"} {:allow-pred local?}))))))))

(deftest a-file-that-the-app-does-not-allow
  (run #(async/then (integrity/verify! "https://example.org/a.mp3" {:type "sri" :value "sha384-AAAA"}
                                       {:allow-pred (constantly false)})
                    (fn [{:keys [valid? error]}]
                      (is (nil? valid?))
                      (is (= [:file/unreachable "https://example.org/a.mp3"] ((juxt :id :url) error)))
                      (is (str/includes? (:reason error) "is not allowed")
                          "no request is sent for it, on either platform")))))

(deftest a-file-that-does-not-come
  (let [url  "https://example.org/a.mp3"
        down (fn [_] (throw (ex-info "Connection refused" {})))
        told (juxt :id :url :status :reason)]
    (run #(async/then (integrity/verify! url {:type "sri" :value "sha384-AAAA"} {:send down})
                      (fn [verdict]
                        (is (nil? (:valid? verdict)))
                        (is (= [:file/unreachable url nil "Connection refused"] (told (:error verdict)))))))))

(deftest a-file-that-the-app-sends-for
  (let [file   (bytes/of (ascii "hello"))
        sha256 "sha256-LPJNul+wow4m6DsqxbninhsWHlwfp0JecwQzYpOLmCQ="
        sent   (atom [])
        send   (fn [request]
                 (swap! sent conj request)
                 {:status 200 :url (:url request) :headers {} :body file})
        verify (fn [value opts]
                 (integrity/verify! "https://example.org/a.mp3" {:type "sri" :value value} opts))]
    (run
     #(async/then (verify sha256 {:send send :allow-pred (constantly true) :headers {"Accept" "audio/*"}})
                  (fn [verdict]
                    (is (= {:valid? true :algorithm "sha256"} verdict))
                    (is (= [["https://example.org/a.mp3" :bytes 600 {"Accept" "audio/*"} true]]
                           (map (juxt :url :as :timeout-seconds :headers (comp some? :allow-pred)) @sent))
                        "one request for the whole file, through the sender of the app")))
     #(async/then (verify "sha256-AAAA" {:send send :timeout-seconds 5})
                  (fn [verdict]
                    (is (= {:valid? false :algorithm "sha256"} verdict))
                    (is (= 5 (:timeout-seconds (peek @sent))))))
     #(async/then (verify sha256 {:send (fn [request] {:status 404 :url (:url request) :headers {}})})
                  (fn [{:keys [valid? error]}]
                    (is (nil? valid?))
                    (is (= [:file/unreachable 404] ((juxt :id :status) error))))))))
