(ns dk.simongray.html.entities
  "The named character references of HTML, such as &amp; and &eacute;, by
  the table in section 13.5 of the HTML Standard.

  The table is the entities.json that WHATWG publishes with the standard,
  as of 2026-10-08, under CC BY 4.0. It's read when the namespace
  compiles, so ClojureScript carries it as one string.

  Only the names of HTML 4.01 are kept, with &apos; of XML and the legacy
  names that HTML reads without a semicolon: 365 of the 2,231 names. The
  rest came to HTML from MathML, such as &NotEqualTilde;, and a reference
  to one of them stays text. They would make the table eight times as
  large, and no description in the corpus of real feeds uses one.

  TODO: read the names from MathML too, by an option or a namespace of
  their own, for HTML that writes mathematics with them."
  #?(:cljs (:require-macros [dk.simongray.html.entities :refer [table]]))
  (:require [clojure.string :as str]
            #?(:clj [clojure.data.json :as json])
            #?(:clj [clojure.java.io :as io])))

#?(:clj
   ;; HTML 4.01, section 24: the entity sets lat1, symbol and special
   (def ^:no-doc html-4-names
     "The names of the character entities of HTML 4.01."
     #{"nbsp" "iexcl" "cent" "pound" "curren" "yen" "brvbar" "sect" "uml" "copy"
       "ordf" "laquo" "not" "shy" "reg" "macr" "deg" "plusmn" "sup2" "sup3"
       "acute" "micro" "para" "middot" "cedil" "sup1" "ordm" "raquo" "frac14"
       "frac12" "frac34" "iquest" "Agrave" "Aacute" "Acirc" "Atilde" "Auml"
       "Aring" "AElig" "Ccedil" "Egrave" "Eacute" "Ecirc" "Euml" "Igrave"
       "Iacute" "Icirc" "Iuml" "ETH" "Ntilde" "Ograve" "Oacute" "Ocirc"
       "Otilde" "Ouml" "times" "Oslash" "Ugrave" "Uacute" "Ucirc" "Uuml"
       "Yacute" "THORN" "szlig" "agrave" "aacute" "acirc" "atilde" "auml"
       "aring" "aelig" "ccedil" "egrave" "eacute" "ecirc" "euml" "igrave"
       "iacute" "icirc" "iuml" "eth" "ntilde" "ograve" "oacute" "ocirc"
       "otilde" "ouml" "divide" "oslash" "ugrave" "uacute" "ucirc" "uuml"
       "yacute" "thorn" "yuml"
       "fnof" "Alpha" "Beta" "Gamma" "Delta" "Epsilon" "Zeta" "Eta" "Theta"
       "Iota" "Kappa" "Lambda" "Mu" "Nu" "Xi" "Omicron" "Pi" "Rho" "Sigma"
       "Tau" "Upsilon" "Phi" "Chi" "Psi" "Omega" "alpha" "beta" "gamma"
       "delta" "epsilon" "zeta" "eta" "theta" "iota" "kappa" "lambda" "mu"
       "nu" "xi" "omicron" "pi" "rho" "sigmaf" "sigma" "tau" "upsilon" "phi"
       "chi" "psi" "omega" "thetasym" "upsih" "piv" "bull" "hellip" "prime"
       "Prime" "oline" "frasl" "weierp" "image" "real" "trade" "alefsym"
       "larr" "uarr" "rarr" "darr" "harr" "crarr" "lArr" "uArr" "rArr" "dArr"
       "hArr" "forall" "part" "exist" "empty" "nabla" "isin" "notin" "ni"
       "prod" "sum" "minus" "lowast" "radic" "prop" "infin" "ang" "and" "or"
       "cap" "cup" "int" "there4" "sim" "cong" "asymp" "ne" "equiv" "le" "ge"
       "sub" "sup" "nsub" "sube" "supe" "oplus" "otimes" "perp" "sdot"
       "lceil" "rceil" "lfloor" "rfloor" "lang" "rang" "loz" "spades" "clubs"
       "hearts" "diams"
       "quot" "amp" "lt" "gt" "OElig" "oelig" "Scaron" "scaron" "Yuml" "circ"
       "tilde" "ensp" "emsp" "thinsp" "zwnj" "zwj" "lrm" "rlm" "ndash" "mdash"
       "lsquo" "rsquo" "sbquo" "ldquo" "rdquo" "bdquo" "dagger" "Dagger"
       "permil" "lsaquo" "rsaquo" "euro"}))

#?(:clj
   (defmacro ^:no-doc table
     "The names and code points of entities.json as one string, a line for
     each name that the table keeps: the name without its ampersand, a
     space, and the code points in hexadecimal, separated by commas."
     []
     (let [entries (json/read-str (slurp (io/resource "dk/simongray/html/entities.json")))
           bare    #(str/replace (subs % 1) #";$" "")
           kept    (into (conj html-4-names "apos")
                         (comp (map key) (remove #(str/ends-with? % ";")) (map bare))
                         entries)]
       (->> entries
            (filter #(kept (bare (key %))))
            (sort-by key)
            (map (fn [[k {:strs [codepoints]}]]
                   (str (subs k 1) " "
                        (str/join "," (map #(Long/toHexString %) codepoints)))))
            (str/join "\n")))))

(defn ^:no-doc code-point->string
  "The character of the code point `n` as a string."
  [n]
  #?(:clj  (String. (Character/toChars (int n)))
     :cljs (js/String.fromCodePoint n)))

(defn- characters
  "The characters of the code points in hexadecimal in `codes`, separated
  by commas."
  [codes]
  (->> (str/split codes #",")
       (map #(code-point->string #?(:clj  (Long/parseLong % 16)
                                     :cljs (js/parseInt % 16))))
       (apply str)))

(def references
  "The characters of each named character reference, by its name without
  the ampersand. A name ends in a semicolon, except for the legacy names
  that HTML also reads without one, so both \"amp;\" and \"amp\" give &."
  (into {}
        (map (fn [line]
               (let [space (str/index-of line " ")]
                 [(subs line 0 space) (characters (subs line (inc space)))])))
        (str/split-lines (table))))

(comment
  (references "eacute;")
  ;; => "é"
  (references "NotEqualTilde;")
  ;; => nil, a name from MathML
  (count references)
  ;; => 365
  #_.)
