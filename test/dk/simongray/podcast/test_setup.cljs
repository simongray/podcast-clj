(ns dk.simongray.podcast.test-setup
  "Give Node a DOMParser, so that the ClojureScript XML adapter can be tested
  outside a browser. The library itself only ever calls `js/DOMParser`; here
  the one from @xmldom/xmldom is installed under that name when the platform
  has none, configured to throw on every error the way browsers report a
  parsererror. In a browser this namespace does nothing."
  (:require ["@xmldom/xmldom" :as xmldom]))

(defn- throwing-parser
  "An xmldom DOMParser that throws on errors instead of logging them."
  []
  (xmldom/DOMParser. #js {:onError (fn [level message _context]
                                     (when-not (= level "warning")
                                       (throw (js/Error. message))))}))

(when-not (exists? js/DOMParser)
  (set! (.-DOMParser js/globalThis) throwing-parser))
