(ns dk.simongray.podcast.sync-test
  "Syncing the profile through like-minded: the profile as the state, what
  its rules keep out, its kind, and the subscription list that a backend
  writes beside the documents. like-minded tests the rest of the sync."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            #?(:clj [clojure.string :as str])
            [clojure.test :refer [deftest is]]
            [dk.simongray.like-minded.document :as document]
            #?(:clj [dk.simongray.like-minded.store :as store])
            [dk.simongray.podcast.profile :as profile]
            #?(:clj [dk.simongray.podcast.profile.opml :as profile.opml])
            [dk.simongray.podcast.sync :as sync]
            [dk.simongray.podcast.test-support :refer [t2 a phone-state laptop-state
                                                       #?@(:clj [t3 t4 failed])]]))

(defn- encoded
  "The document of the profile `state` written at `at`."
  [state at]
  (document/encode state at sync/defaults))

(deftest the-profile-by-default
  (let [fetched [{:name "phone.edn" :body (encoded (phone-state) t2)}
                 {:name "laptop.edn" :body (encoded (laptop-state) t2)}
                 {:name "poison.edn" :body (pr-str {:version 1 :device "x" :written-at t2
                                                    :state {:episodes {a {:updated-at "soon" :device "x"}}}})}]
        {:keys [state report]} (sync/reconcile (profile/make "tablet") fetched t2)]
    (is (= [[:sync/unreadable-document "poison.edn"]] (map (juxt :id :name) report))
        "read with the rules of the profile")
    (is (= 900 (:position (profile/episode-entry state a))) "and merged by them")
    (is (= #{"feed-a" "feed-b"} (set (keys (profile/subscriptions state)))))
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error) #"can't be merged"
                          (document/decode (pr-str {:version 1 :state {:clock "now"}}) sync/defaults)))))

(deftest the-kind-of-a-profile
  (is (= sync/kind (:kind (document/decode (encoded (phone-state) t2) sync/defaults))))
  (let [notes {:name "notes.edn" :body (pr-str {:version 1 :device "x" :written-at t2 :kind "my-notes" :state {}})}]
    (is (= [:sync/other-kind] (map :id (:report (document/states [notes] t2 sync/defaults))))
        "a document of another app is left out")))

#?(:clj
   (deftest a-profile-that-the-others-cannot-merge
     (let [documents (atom {})
           backend   (sync/backend (store/memory documents))
           phone     (sync/sync! (phone-state) [backend] {:at t2})
           written   (:version (@documents "phone.edn"))
           broken    (assoc-in (:state phone) [:queue ["feed-a" "x"]] {:rank "V"})
           {:keys [report]} (sync/sync! broken [backend] {:at t4 :cursors (:cursors phone)})]
       (is (= [[:sync/malformed-state :error]] (map (juxt :id :level) report)))
       (is (str/includes? (:error (first report)) "not stamped"))
       (is (= written (:version (@documents "phone.edn"))) "nothing written"))))

#?(:clj
   (deftest one-step-at-a-time
     (let [documents (atom {})
           fetched   (atom [])
           counted   (update (store/memory documents) :fetch
                             (fn [f] (fn [& args] (swap! fetched conj (first args)) (apply f args))))
           backend   (sync/backend counted)
           _         (sync/sync! (laptop-state) [backend] {:at t2})
           step      (sync/stepper [backend] {:at t3})
           state     (do (reset! fetched []) (step (phone-state)))
           first-run @fetched]
       (is (= #{"feed-a" "feed-b"} (set (keys (profile/subscriptions state))))
           "the state, with what the other device wrote")
       (is (= ["laptop.edn"] first-run))
       (reset! fetched [])
       (is (= state (step state)))
       (is (= [] @fetched) "the next step starts from the cursors of the last"))))

#?(:clj
   (deftest a-folder-of-profiles-and-their-subscription-list
     (let [documents (atom {})
           backend   (sync/backend (store/memory documents) {:opml? true})
           phone     (sync/sync! (phone-state) [backend] {:at t2})
           laptop    (sync/sync! (laptop-state) [backend] {:at t3})]
       (is (= [nil nil] (map failed [phone laptop])))
       (is (= #{"phone.edn" "laptop.edn" "subscriptions.opml"} (set (keys @documents))))
       (is (str/includes? (:body (@documents "subscriptions.opml")) "https://a/rss"))
       (is (str/includes? (:body (@documents "subscriptions.opml")) "https://b/rss")
           "the list of the merged profile")
       (is (= {"laptop.edn" "1" "phone.edn" "1"} (:versions (get (:cursors laptop) "memory")))
           "the cursor follows the documents, not the subscription list"))))

#?(:clj
   (deftest the-subscription-list-beside-the-documents
     (let [documents (atom {})
           backend   (sync/backend (store/memory documents) {:opml? true})
           mirror    #(get @documents sync/opml-name)
           step      (fn [{:keys [cursors]} state at]
                       (assoc (sync/sync! state [backend] {:at at :cursors cursors}) :mirror (:version (mirror))))
           began     (step nil (phone-state) t2)
           heard     (step began (profile/progress (:state began) a 500 3600 t3) t3)
           sorted    (step heard
                           (-> (:state heard)
                               (profile/configure "feed-a" {:folder "News"} t3)
                               (profile/tag "feed-a" "Daily" t3))
                           t3)
           followed  (step sorted (profile.opml/follow-list (:state sorted) "https://l.example/picks.opml" {:title "Picks"} t4) t4)]
       (is (= [nil nil nil nil] (map failed [began heard sorted followed])))
       (is (= ["1" "1" "2" "3"] (map :mirror [began heard sorted followed]))
           "written when the folders, the tags or the lists change, and not for a position")
       (is (re-find #"<outline text=\"News\">\s*<outline[^>]*category=\"Daily\"" (:body (mirror))))
       (is (str/includes? (:body (mirror)) "type=\"include\"")))))

#?(:clj
   (deftest one-shared-profile-and-its-subscription-list
     (let [documents (atom {})
           backend   (sync/backend (dissoc (store/memory documents) :list) {:opml? true})
           phone     (sync/sync! (phone-state) [backend] {:at t2})]
       (is (= #{"podcast.edn" "subscriptions.opml"} (set (keys @documents))) "podcast.edn by default")
       (is (str/includes? (:body (@documents "subscriptions.opml")) "https://a/rss"))
       (sync/sync! (:state phone) [backend] {:at t3 :cursors (:cursors phone)})
       (is (= "1" (:version (@documents "subscriptions.opml"))) "and not again while the subscriptions stay"))))
