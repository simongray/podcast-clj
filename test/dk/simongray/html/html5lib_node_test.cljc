(ns dk.simongray.html.html5lib-node-test
  "The tokenizer against the tokenizer tests of html5lib-tests, on the JVM
  and in Node, when the tests are in dev-resources/html5lib-tests, as
  doc/development.md tells. Without them, nothing is checked.

  What the tokenizer leaves out is left out of the tests too:

  - a test that uses a name that the table of dk.simongray.html.entities
    leaves out, one of those from MathML, is skipped
  - a DOCTYPE token is compared without its details
  - a test that starts in the CDATA section state is skipped
  - a test with a processing instruction, which the tokenizer reads as a
    bogus comment, is skipped"
  (:require #?(:cljs ["fs" :as fs])
            #?(:clj [clojure.data.json :as json])
            #?(:clj [clojure.java.io :as io])
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [dk.simongray.html.entities :as entities]
            [dk.simongray.html.tokenizer :as tokenizer]))

(def all-names
  "Every name of a character reference in HTML, without the ampersand, by
  the entities.json of the standard."
  (->> #?(:clj  (json/read-str (slurp (io/resource "dk/simongray/html/entities.json")))
          :cljs (js->clj (js/JSON.parse
                          (fs/readFileSync "resources/dk/simongray/html/entities.json" "utf8"))))
       keys
       (map #(subs % 1))
       set))

(defn left-out?
  "Whether the text `s` refers to a name that the table leaves out, by the
  longest match of the names of the standard."
  [s]
  (some (fn [[_ run]]
          (when-let [name (some #(let [prefix (subs run 0 %)]
                                   (when (all-names prefix) prefix))
                                (range (count run) 0 -1))]
            (not (contains? entities/references name))))
        (re-seq #"&([A-Za-z0-9]+;?)" s)))

(def dir
  "Where the tokenizer tests of html5lib-tests are."
  "dev-resources/html5lib-tests/tokenizer")

(def states
  "The state of the tokenizer for each name of a state in the tests."
  {"Data state"        :data
   "PLAINTEXT state"   :plaintext
   "RCDATA state"      :rcdata
   "RAWTEXT state"     :rawtext
   "Script data state" :script-data})

(defn test-files
  "The contents of the files of tokenizer tests, by name, or none when
  they're not there."
  []
  #?(:clj  (let [files (.listFiles (io/file dir))]
             (into (sorted-map)
                   (for [^java.io.File file files
                         :when (str/ends-with? (.getName file) ".test")]
                     [(.getName file) (json/read-str (slurp file))])))
     :cljs (if (fs/existsSync dir)
             (into (sorted-map)
                   (for [name  (fs/readdirSync dir)
                         :when (str/ends-with? name ".test")]
                     [name (js->clj (js/JSON.parse (fs/readFileSync (str dir "/" name) "utf8")))]))
             {})))

(defn unescaped
  "The text `s` with each \\uHHHH in it turned into its character, as the
  tests that are double escaped need."
  [s]
  (str/replace s
               #"\\u([0-9A-Fa-f]{4})"
               (fn [[_ hex]]
                 #?(:clj  (str (char (Integer/parseInt hex 16)))
                    :cljs (js/String.fromCharCode (js/parseInt hex 16))))))

(defn coalesced
  "The tokens `ts` in the form of the tests, with adjacent characters
  joined."
  [ts]
  (reduce (fn [acc t]
            (let [prev (peek acc)]
              (if (and (= "Character" (first t)) (= "Character" (first prev)))
                (conj (pop acc) ["Character" (str (second prev) (second t))])
                (conj acc t))))
          []
          ts))

(defn test-form
  "The token `t` of the tokenizer in the form of the tests."
  [t]
  (if (string? t)
    ["Character" t]
    (case (:type t)
      :start-tag              (cond-> ["StartTag" (:name t) (:attrs t)]
                                (:self-closing? t) (conj true))
      :end-tag                ["EndTag" (:name t)]
      :comment                ["Comment" (:data t)]
      :doctype                ["DOCTYPE"])))

(defn cases
  "Each test of the tokenizer tests, once for each of its initial states."
  []
  (for [[file tests] (test-files)
        test         (get tests "tests")
        state        (get test "initialStates" ["Data state"])
        :let         [fix (if (get test "doubleEscaped") unescaped identity)]]
    {:file        file
     :description (get test "description")
     :state       state
     :input       (fix (get test "input"))
     :last        (get test "lastStartTag")
     :expected    (coalesced (for [t (get test "output")]
                               (if (= "DOCTYPE" (first t))
                                 ["DOCTYPE"]
                                 (mapv #(if (string? %) (fix %) %) t))))}))

(defn actual
  "The tokens of the test `c` in the form of the tests."
  [{:keys [input state last]}]
  (coalesced (map test-form (tokenizer/tokens input {:state          (states state)
                                                     :last-start-tag last
                                                     :text-states    {}}))))

(defn processing-instruction?
  "Whether the test `c` has a processing instruction: one in its expected
  tokens, or one that the end of its input cuts off, which the standard
  drops and a bogus comment keeps."
  [{:keys [input expected]}]
  (or (some #(= "ProcessingInstruction" (first %)) expected)
      (when-let [k (str/last-index-of input "<?")]
        (and (not (str/index-of input ">" k))
             (re-find #"^(?:$|[A-Za-z_])" (subs input (+ k 2)))))))

(deftest the-tokenizer-tests-of-html5lib
  (doseq [c     (cases)
          :when (and (states (:state c))
                     (not (left-out? (:input c)))
                     (not (processing-instruction? c)))]
    (is (= (:expected c) (actual c))
        (str (:file c) ": " (:description c) " in the " (:state c)))))
