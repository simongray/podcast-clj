(ns dk.simongray.podcast.feed.discovery.impl-test
  "The internals of feed.discovery: the name of a page, the sniff of a
  feed and the website of a feed."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            [clojure.test :refer [deftest is]]
            [dk.simongray.podcast.feed.discovery.impl :as discovery.impl]))

(deftest the-name-of-a-page
  (is (= "The Show" (discovery.impl/page-name "<title>Home | The Show</title>" "https://show.example/"))
      "the longest part of a title")
  (is (= "The Show"
         (discovery.impl/page-name "<meta property=og:site_name content='The Show'><title>Home – The Show</title>"
                              "https://show.example/"))
      "a home page by the name of its site")
  (is (= "CrimeLess"
         (discovery.impl/page-name "<meta content=iHeart property=og:site_name><meta content='CrimeLess | iHeart' property=og:title>"
                              "https://www.iheart.com/podcast/crimeless/"))
      "another page by its title, without the name of the site")
  (is (nil? (discovery.impl/page-name "<p>No name</p>" "https://a.example/"))))

(deftest a-feed-or-a-page
  (is (discovery.impl/feed-document? "<?xml version=\"1.0\"?>\n<?xml-stylesheet href=\"s.xsl\"?>\n<!-- a > b -->\n<rss version=\"2.0\">"))
  (is (discovery.impl/feed-document? "<feed xmlns=\"http://www.w3.org/2005/Atom\">"))
  (is (discovery.impl/feed-document? "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">"))
  (is (not (discovery.impl/feed-document? "<!DOCTYPE html><html><head><title>rss</title></head>")))
  (is (not (discovery.impl/feed-document? "<rss-reader>")) "an element whose name only starts like one")
  (is (discovery.impl/feed-document? (str (apply str (repeat 800 "<?a?>")) "<rss>"))
      "many processing instructions, and the stack holds")
  (is (not (discovery.impl/feed-document? (str (apply str (repeat 3000 "<!---->")) "<rss>")))
      "only the start of a long document counts"))

(deftest the-website-of-a-feed
  (is (discovery.impl/same-website? "https://Example.com" "http://www.example.com/podcast/"))
  (is (discovery.impl/same-website? "https://example.com/show/" "https://www.example.com/show"))
  (is (not (discovery.impl/same-website? "https://example.com/show" "https://example.com/other")))
  (is (not (discovery.impl/same-website? "https://alice.substack.com/" "https://bob.substack.com/"))
      "only www. is left out of a domain")
  (is (not (discovery.impl/same-website? "https://example.com/" nil))))
