(ns dk.simongray.podcast.util.url-test
  "URLs and their hosts: URLs made absolute as a browser does it, made
  legal on the JVM, the parameters of a query, and the hosts of the public
  internet."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast.util.url :as url]))

(deftest urls-made-absolute
  (let [page "https://a.example/show/feed.xml"]
    (testing "as a browser resolves them"
      (is (= "https://a.example/show/feed.xml?page=2" (url/absolute-url page "?page=2")))
      (is (= "https://a.example/show/page2.xml" (url/absolute-url page "page2.xml")))
      (is (= "https://a.example/feed.xml" (url/absolute-url page "/feed.xml")))
      (is (= "https://a.example/other/feed.xml" (url/absolute-url page "../other/feed.xml")))
      (is (= "https://cdn.example/x.xml" (url/absolute-url page "//cdn.example/x.xml")))
      (is (= "https://b.example/f.xml" (url/absolute-url page "https://b.example/f.xml"))))
    (testing "a base without a path, and a fragment alone"
      (is (= "https://a.example/feed.xml" (url/absolute-url "https://a.example" "feed.xml")))
      (is (= "https://a.example/show/feed.xml#x" (url/absolute-url (str page "#top") "#x"))))
    (testing "a path that goes above the root stays at the root"
      (is (= "https://a.example/x" (url/absolute-url page "../../../x")))
      (is (= "https://a.example/x" (url/absolute-url page "/../x")))
      (is (= "https://a.example/" (url/absolute-url page "../..")))
      (is (= "https://b.example/x" (url/absolute-url page "https://b.example/../x"))))
    (testing "no reference is the document itself, without its fragment"
      (is (= "https://a.example/show/feed.xml?page=2" (url/absolute-url (str page "?page=2#top") ""))))
    (testing "the spaces around a URL, and the line breaks in it"
      (is (= "https://b.example/f.xml" (url/absolute-url page " https://b.example/f.xml\n")))
      (is (= "https://a.example/feed.xml" (url/absolute-url (str " " page) "\n/feed.xml")))
      (is (= "https://a.example/show/x" (url/absolute-url page (str (apply str (repeat 100000 " ")) "x")))))
    (testing "a base that is no URL, or that holds no path"
      (is (every? nil? (map #(url/absolute-url % "x") ["not a url" "/only/a/path" "mailto:a@b.example" nil])))
      (is (nil? (url/absolute-url page nil))))))

#?(:clj
   (deftest urls-the-wild-hands-out
     (testing "spaces and non-ASCII letters are percent-encoded as UTF-8"
       (is (= "https://example.com/Crazy%20Cloud/crazy%20cloud.xml"
              (url/legal-url "https://example.com/Crazy Cloud/crazy cloud.xml")))
       (is (= "https://example.com/bl%C3%A5b%C3%A6r.xml"
              (url/legal-url "https://example.com/blåbær.xml")))
       (is (= "https://example.com/%F0%9F%8E%99.xml"
              (url/legal-url "https://example.com/🎙.xml"))))
     (testing "a legal URL comes back unchanged, escapes and all"
       (is (= "https://example.com/a%20b.xml?q=1&r[]=2#frag"
              (url/legal-url "https://example.com/a%20b.xml?q=1&r[]=2#frag"))))
     (testing "a bracket in the path, and a percent sign that starts no escape, which java.net.URI refuses"
       (doseq [[u legal] [["https://example.com/show[1]/feed.xml?r[]=2" "https://example.com/show%5B1%5D/feed.xml?r[]=2"]
                          ["https://example.com/100%.mp3" "https://example.com/100%25.mp3"]
                          ["https://example.com/a?x=100%" "https://example.com/a?x=100%25"]
                          ["http://[::1]:8080/a b" "http://[::1]:8080/a%20b"]]]
         (is (= legal (url/legal-url u)))
         (is (java.net.URI. (url/legal-url u)))))))

(deftest urls-of-the-web
  (is (every? url/web-url? ["https://a.example/feed.xml" "HTTP://a.example" "https://user:pass@a.example/x"]))
  (is (not-any? url/web-url? ["https://" "ftp://a.example/x" "a.example/x" " https://a.example/" "" nil 5
                              "javascript:alert(1)"])))

(deftest parameters-of-a-query
  (is (= "https://a.example/feed.xml?page=2" (url/with-query "https://a.example/feed.xml" {:page 2})))
  (is (= "https://a.example/feed.xml?x=1&page=2&token=a%20b#top"
         (url/with-query "https://a.example/feed.xml?x=1#top" [[:page 2] [:token "a b"]]))
      "after the parameters that the URL has, and before its fragment")
  (is (= "https://a.example/feed.xml?page=2" (url/with-query "https://a.example/feed.xml?" {:page 2})))
  (is (= "https://a.example/feed.xml?x=1&page=2" (url/with-query "https://a.example/feed.xml?x=1&" {:page 2})))
  (is (= "https://a.example/feed.xml" (url/with-query "https://a.example/feed.xml" {:page nil}))
      "no parameters, no change")
  (is (= {"since" "1727000000" "podcast" "https://example.org/feed.xml" "q" "a b c" "flag" nil}
         (url/query-params "since=1727000000&podcast=https%3A%2F%2Fexample.org%2Ffeed.xml&q=a+b%20c&flag&=x"))
      "the parameters of a query, decoded"))

(deftest hosts-on-the-public-internet
  (is (not-any? url/public? ["http://127.0.0.1/" "http://10.1.2.3/" "http://172.16.0.1/" "http://192.168.1.1/"
                             "http://169.254.169.254/latest/meta-data" "http://100.64.0.1/" "http://0.0.0.0/"
                             "http://224.0.0.1/" "http://[::1]/" "http://[fd00::1]/" "http://[fe80::1]/"
                             "http://[::ffff:127.0.0.1]/" "http://[64:ff9b::7f00:1]/" "http://[2002:a00:1::1]/"
                             "http://localhost:8080/" "not a url"])
      "loopback, private, link-local, shared, multicast and local addresses, and IPv6 that holds them")
  (is (not-any? url/public? ["http://192.88.99.2/" "http://[64:ff9b:1:a00:1::]/" "http://[64:ff9b:2::808:808]/"
                             "http://[100:0:0:1::1]/" "http://[2001:2::1]/" "http://[3fff::1]/"
                             "http://[3fff:fff::1]/" "http://[5f00::1]/"])
      "the blocks that the registries of IANA got after 2015")
  (is (every? url/public? ["http://8.8.8.8/" "https://[2606:4700:4700::1111]/" "http://[64:ff9b::808:808]/"
                           "http://[3fff:1000::1]/" "http://[2001:4:112::1]/"])))

#?(:cljs
   (deftest names-in-a-browser
     (is (not-any? url/public? ["http://localhost/" "https://printer.local/" "https://wiki.internal/"]))
     (is (not-any? url/public? ["http://localhost./" "https://nas.local./" "https://router.home.arpa/"
                                "https://nas/" "https://intranet./" "http://127.0.0.1./"])
         "a name with a dot at its end, and a name without a dot, which is a name of the local network")
     (is (every? url/public? ["https://podcastindex.org/" "https://podcastindex.org./"])
         "a name that JavaScript does not look up")))

(deftest names-of-the-public-internet
  (is (every? url/public-name? ["podcastindex.org" "PodcastIndex.org." "a.b.example"]))
  (is (not-any? url/public-name? ["localhost" "localhost." "nas" "nas.local" "NAS.Local." "wiki.internal"
                                  "router.home.arpa" "x.localhost" "" nil])))
