(ns dk.simongray.podcast.util.secret
  "Texts that don't show when they're printed: a secret key, a password,
  a token.

  The hide function wraps a text. The functions pr and str and the pretty
  printer write a hidden text like this, on both platforms:

      #<secret>

  So a log or a REPL that prints a map with a secret in it doesn't show
  the secret. The reveal function gives the text, for the one place that
  needs it.

  The reader doesn't read that text. So a map with a hidden text doesn't
  go to a file as EDN and come back without its secret, since the reading
  fails.

  This keeps a secret from a print by accident. It doesn't keep it from
  code that looks for it, since a debugger and the console of a browser
  show the fields of an object."
  #?(:clj (:import [java.io Writer])))

(def ^:no-doc shown
  "What the printer writes in the place of a hidden text."
  "#<secret>")

#?(:clj
   (deftype Hidden [text]
     Object
     (toString [_] shown)
     (equals [_ other]
       (and (instance? Hidden other)
            (= text (.-text ^Hidden other))))
     (hashCode [_] (hash text)))

   :cljs
   (deftype Hidden [text]
     Object
     (toString [_] shown)
     (toJSON [_] shown)
     IEquiv
     (-equiv [_ other]
       (and (instance? Hidden other)
            (= text (.-text other))))
     IHash
     (-hash [_] (hash text))
     IPrintWithWriter
     (-pr-writer [_ writer _] (-write writer shown))))

#?(:clj
   (defmethod print-method Hidden
     [_ ^Writer writer]
     (.write writer ^String shown)))

#?(:clj
   (defmethod print-dup Hidden
     [_ ^Writer writer]
     (.write writer ^String shown)))

(defn hidden?
  "Whether `x` is a text that hide wrapped."
  [x]
  (instance? Hidden x))

(defn hide
  "The text `s` as a value that the printer doesn't show. A hidden text
  stays as it is, and so does nil. Two hidden texts are equal when their
  texts are."
  [s]
  (if (or (nil? s) (hidden? s))
    s
    (->Hidden s)))

(defn reveal
  "The text that `x` hides. A value that isn't hidden stays as it is, so
  the function takes a plain text too."
  [x]
  (if (hidden? x)
    (.-text ^Hidden x)
    x))

(comment
  (def token
    (hide "s3cr3t"))

  ;; a map with a secret, as a log writes it
  (pr-str {:user "ann" :token token})
  (str token)

  (reveal token)
  (= token (hide "s3cr3t"))
  #_.)
