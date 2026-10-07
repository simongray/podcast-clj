(ns dk.simongray.podcast.value.bolt11
  "The invoices of BOLT 11, as far as an app that pays needs to read them:
  the network and the amount, the payment hash, and the proof of payment.

  An invoice is the text that a Lightning address or a wallet gives, e.g.
  lnbc2500u1…, and a wallet pays it. None of these functions checks the
  signature, since the wallet does when it pays. BOLT 11 is at
  https://github.com/lightning/bolts/blob/master/11-payment-encoding.md"
  (:require [clojure.string :as str]
            [dk.simongray.podcast.util.bech32 :as bech32]
            [dk.simongray.podcast.util.bytes :as bytes]))

(def ^:no-doc max-invoice-length
  "The most characters of an invoice that podcast-clj reads: the most
  digits that one QR code holds. BOLT 11 gives no limit, so the limit is a
  choice."
  7089)

(def ^:no-doc networks
  "The network of each prefix of a BOLT 11 invoice (part Human-Readable
  Part), and sb, which btcd has for its simnet."
  {"bc"   :bitcoin
   "tb"   :testnet
   "tbs"  :signet
   "bcrt" :regtest
   "sb"   :simnet})

(def ^:no-doc multipliers
  "The millisatoshis in one of each unit of an amount in a BOLT 11 invoice,
  a fraction of a bitcoin."
  {""  100000000000
   "m" 100000000
   "u" 100000
   "n" 100})

(def ^:no-doc invoice-form
  "The pattern of a BOLT 11 invoice in lower case: the prefix of its
  network, its amount and unit, if it has one, and a 1 before six or more
  characters of Bech32 data."
  (re-pattern (str "ln(bcrt|bc|tbs|tb|sb)"
                   "(?:([1-9][0-9]{0,15})([munp]?))?"
                   "1[02-9ac-hj-np-z]{6,}")))

(defn invoice-amount
  "The network and the amount of the BOLT 11 `invoice` from the part
  before its data, as a map of :network and :msat, or nil for text that
  isn't an invoice. An invoice of any amount has no :msat.

  The amount is in millisatoshis. It starts with no zero, and a pico amount
  must end in 0, as BOLT 11 requires. After the amount come a 1 and the
  data, which are six or more characters of Bech32."
  [invoice]
  (when (and (string? invoice)
             (<= (count invoice) max-invoice-length)
             (or (= invoice (str/lower-case invoice))
                 (= invoice (str/upper-case invoice))))
    (when-let [[_ prefix digits unit] (->> (str/lower-case invoice)
                                           (re-matches invoice-form))]
      (let [network (networks prefix)
            factor  (multipliers unit)]
        (cond
          (nil? digits)
          {:network network}

          ;; a tenth of the amount, from its digits, since JavaScript holds no
          ;; whole number of sixteen digits
          (= "p" unit)
          (when (str/ends-with? digits "0")
            {:network network
             :msat    (parse-long (subs digits 0 (dec (count digits))))})

          :else
          (let [n (parse-long digits)]
            (when (and n (<= n (quot 9007199254740991 factor)))
              {:network network :msat (* n factor)})))))))

(defn- tagged-fields
  "The tagged fields in the data `words` of an invoice, numbers of five
  bits, as pairs of a type and data in their order. It's nil for data
  that aren't those of an invoice.

  The section Data Part of BOLT 11 defines the data: a time of 7 numbers,
  the tagged fields, and a signature of 104 numbers. A field is a type,
  a length of two numbers and its data."
  [words]
  (let [end (- (count words) 104)]
    (loop [i 7 out []]
      (cond
        (= i end)
        out

        (< end (+ i 3))
        nil

        :else
        (let [n    (long (+ (* 32 (nth words (+ i 1))) (nth words (+ i 2))))
              next (+ i 3 n)]
          (when (<= next end)
            (let [field [(nth words i) (subvec words (+ i 3) next)]]
              (recur next (conj out field)))))))))

(defn payment-hash
  "The payment hash of the BOLT 11 `invoice`, as hexadecimal text of 32
  bytes, or nil for a text that isn't an invoice with one payment hash.

  The hash is the tagged field p, with 52 numbers of five bits and zero
  bits to pad, as the section Tagged Fields of BOLT 11 defines it. The
  reader checks the checksum of the invoice."
  [invoice]
  (when (invoice-amount invoice)
    (let [limits  {:max-length max-invoice-length}
          decoded (bech32/decode-words invoice limits)]
      (when-let [fields (some-> decoded :words tagged-fields)]
        (let [hashes (filter #(= 1 (first %)) fields)
              data   (second (first hashes))]
          (when (and (= 1 (count hashes))
                     (= 52 (count data)))
            (some-> (bech32/regroup data 5 8 false) bytes/of bytes/hex)))))))

(defn proves?
  "Whether `preimage` is the proof that somebody paid the BOLT 11
  `invoice`: its SHA-256 is the payment hash of the invoice.

  A wallet gets the preimage from the recipient when the payment arrives,
  and no one can guess it. The `preimage` is hexadecimal text of 32
  bytes. It's false for another text, and for an invoice without a
  payment hash that payment-hash reads."
  [preimage invoice]
  (boolean (and (string? preimage)
                (re-matches #"[0-9a-fA-F]{64}" preimage)
                (when-let [hash (payment-hash invoice)]
                  (let [digest (bytes/sha256 (bytes/from-hex preimage))]
                    (= hash (bytes/hex digest)))))))

(comment
  (invoice-amount "lnbc2500u1pvjluez")
  ;; => {:network :bitcoin :msat 250000000}
  #_.)
