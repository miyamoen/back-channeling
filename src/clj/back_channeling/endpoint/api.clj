(ns back-channeling.endpoint.api
  (:require [clojure.data.json :as json]
            [clojure.tools.logging :as log]
            [clj-time.format :as time-fmt]
            [clj-time.coerce :refer [from-date to-date]]
            [compojure.core :refer [context ANY]]
            [compojure.coercions  :refer :all]
            [liberator.representation :refer [ring-response]]
            [liberator.core :as liberator]
            [bouncer.validators :as v]
            (back-channeling [util :refer [parse-request]])
            (back-channeling.component [datomic :as d]
                                       [token :as token]
                                       [socketapp :refer [broadcast-message multicast-message]]
                                       [tag :as tag]
                                       [user :as user]))
  (:import [java.util Date UUID]
           [java.nio.file Files Paths CopyOption]
           [java.nio.file.attribute FileAttribute]))

(def iso8601-formatter (time-fmt/formatters :basic-date-time))

(extend-type java.util.Date
  json/JSONWriter
  (-write [date out]
    (json/-write (time-fmt/unparse iso8601-formatter (from-date date)) out)))

(extend-type java.util.UUID
  json/JSONWriter
  (-write [uuid out]
    (json/-write (.toString uuid) out)))

(defn save-board [datomic board]
  (let [board-id (d/tempid :db.part/user)
        qs (map (fn [tag-id] [:db/add board-id :board/tags tag-id]) (:board/tags board))
        tempids (-> (d/transact datomic
                                (conj qs {:db/id board-id
                                          :board/name (:board/name board)
                                          :board/description (:board/description board)}))
                    :tempids)]
    [tempids board-id]))

(defn boards-resource [{:keys [datomic]}]
  (liberator/resource
   :available-media-types ["application/edn" "application/json"]
   :allowed-methods [:get :post]
   :malformed? #(parse-request % {:board/name [[v/required]
                                               [v/max-count 255]]})

   :allowed? (fn [{{:keys [request-method identity]} :request}]
               (let [permissions (:permissions identity)]
                 (condp = request-method
                   :get (:list-boards permissions)
                   :post (:create-board permissions)
                   false)))

   :exists? (fn [ctx]
              (if-let [boards (d/query datomic
                                      '{:find [[(pull ?board [:*]) ...]]
                                        :where [[?board :board/name]]})]
                {::boards boards}
                false))

   :post! (fn [{board :edn req :request}]
            (let [[tempids board-id] (save-board datomic board)]
              {:db/id (d/resolve-tempid datomic tempids board-id)}))

   :handle-created (fn [ctx]
                     {:db/id (:db/id ctx)})

   :handle-ok (fn [{boards ::boards}]
                boards)))

(defn board-resource [{:keys [datomic]} board-name]
  (liberator/resource
   :available-media-types ["application/edn" "application/json"]
   :allowed-methods [:get :put]
   :malformed? #(parse-request %)
   :allowed? (fn [{{:keys [request-method identity]} :request}]
               (let [permissions (:permissions identity)]
                 (condp = request-method
                   :get (and (:list-boards permissions)
                             (or (:list-any-threads permissions)
                                 (:list-threads permissions)))
                   :put (or (:modify-boards permissions))
                   false)))

   :exists? (fn [ctx]
              (if-let [board (d/query datomic
                                      '{:find [(pull ?board [:*]) .]
                                        :in [$ ?b-name]
                                        :where [[?board :board/name ?b-name]]}
                                      board-name)]
                {::board board}
                false))

   :put! (fn [{old ::board new :edn}]
           (d/transact datomic
             {:db/id (:db/id old)
              :board/name (:board/name new)
              :board/description (:board/description new)}))

   :handle-ok (fn [ctx]
                (let [board (::board ctx)
                      permissions (get-in ctx [:request :identity :permissions])
                      user-name (get-in ctx [:request :identity :user/name])]
                  (->> (d/query datomic
                                '{:find [?thread (count ?comment)]
                                  :in [$ ?board]
                                  :where [[?board :board/threads ?thread]
                                          [?thread :thread/comments ?comment]]}
                                (:db/id board))
                       (map #(-> (d/pull datomic
                                         '[:db/id :thread/title :thread/since :thread/last-updated :thread/open?
                                           {:thread/watchers [:user/name]}
                                           {:thread/posted-by [:user/name]}
                                           {:thread/tags [:tag/name :tag/priority
                                                          {:tag/color [:db/ident]}]}]
                                         (first %))
                                 (assoc :thread/resnum (second %))
                                 (update-in [:thread/watchers]
                                            (fn [watchers]
                                              (->> watchers
                                                   (map :user/name)
                                                   (apply hash-set))))))
                       (filter #(or (:list-any-threads permissions)
                                    (and (:list-threads permissions)
                                         (or (:thread/open? %)
                                             (= user-name (get-in % [:thread/posted-by :user/name]))))))
                       ((fn [threads] (assoc board :board/threads threads))))))))

(defn save-thread [datomic board-name th user]
  (let [now (Date.)
        thread-id (d/tempid :db.part/user)
        tempids (-> (d/transact datomic
                                [[:db/add [:board/name board-name]
                                  :board/threads thread-id]
                                 {:db/id thread-id
                                  :thread/title (:thread/title th)
                                  :thread/posted-by user
                                  :thread/since now
                                  :thread/last-updated now
                                  :thread/open? (:thread/open? th true)}
                                 [:db/add thread-id :thread/comments #db/id[:db.part/user -2]]
                                 {:db/id #db/id[:db.part/user -2]
                                  :comment/posted-at now
                                  :comment/posted-by user
                                  :comment/format (get th :comment/format :comment.format/plain)
                                  :comment/content (:comment/content th)}])
                    :tempids)]
    [tempids thread-id]))

(defn threads-resource [{:keys [datomic socketapp]} board-name]
  (liberator/resource
   :available-media-types ["application/edn" "application/json"]
   :allowed-methods [:get :post]
   :malformed? #(parse-request % {:thread/title [[v/required]
                                                 [v/max-count 255]]
                                  :comment/content [[v/required]
                                                    [v/max-count 4000]]})
   :authorized? (fn [ctx]
                  (if-let [identity (get-in ctx [:request :identity])]
                    {::identity identity}
                    false))

   :allowed? (fn [{{:keys [request-method identity]} :request}]
               (let [permissions (:permissions identity)]
                 (condp = request-method
                   :get (or (:list-any-threads permissions) (:list-threads permissions))
                   :post (:create-thread permissions)
                   false)))

   :handle-created (fn [ctx]
                     {:db/id (:db/id ctx)})

   :post! (fn [{th :edn identity ::identity}]
            (let [user (d/query datomic
                                '{:find [?u .]
                                  :in [$ ?name]
                                  :where [[?u :user/name ?name]]}
                                (:user/name identity))
                  [tempids thread-id] (save-thread datomic board-name th user)]
              (broadcast-message socketapp [:update-board {:board/name board-name}])
              {:db/id (d/resolve-tempid datomic tempids thread-id)}))

   :handle-ok (fn [{{{:keys [q]} :params identity :identity} :request}]
                (let [permissions (:permissions identity)
                      user-name (:user/name identity)]
                  (when q
                    (->> (d/query datomic
                                  '{:find [?board-name ?thread ?comment ?score]
                                    :in [$ ?board-name ?search]
                                    :where [[?board :board/name ?board-name]
                                            [?board :board/threads ?thread]
                                            [?thread :thread/comments ?comment]
                                            [(fulltext $ :comment/content ?search)
                                             [[?comment ?content ?tx ?score]]]]}
                                  board-name q)
                         (map (fn [[board-name thread-id comment-id score]]
                                (let [thread (d/pull datomic
                                                     '[:db/id :thread/title :thread/open?
                                                       {:thread/watchers [:user/name]}
                                                       {:thread/posted-by [:user/name]}]
                                                     thread-id)
                                      comment (d/pull datomic '[:comment/content] comment-id)]
                                  (merge thread comment
                                         {:score/value score}
                                         {:board/name board-name}))))
                         (group-by :db/id)
                         (filter #(or (:list-any-threads permissions)
                                      (and (:list-threads permissions)
                                           (or (:thread/open? %)
                                               (= user-name (get-in % [:thread/posted-by :user/name]))))))
                         (map (fn [[k v]]
                                (apply max-key :score/value v)))
                         (sort-by :score/value >)
                         vec))))))

(defn open-thread? [datomic thread-id]
  (d/query datomic
           '{:find [?o .]
             :in [$ ?th]
             :where [[?th :thread/open? ?o]]}
           thread-id))

(defn thread-created-by? [datomic thread-id user-name]
  (d/query datomic
           '{:find [?th .]
             :in [$ ?th ?u-name]
             :where [[?th :thread/posted-by ?u]
                     [?u :user/name ?u-name]]}
           thread-id user-name))

(defn read-thread? [datomic permissions thread-id user-name]
  (and (:list-comments permissions)
       (or (:list-any-threads permissions)
           (and (:list-threads permissions)
                (or (open-thread? datomic thread-id)
                    (thread-created-by? datomic thread-id user-name))))))

(defn thread-resource [{:keys [datomic]} thread-id]
  (liberator/resource
   :available-media-types ["application/edn" "application/json"]
   :allowed-methods [:get :put]
   :malformed? #(parse-request %)
   :allowed? (fn [{{:keys [request-method identity]} :request}]
               (let [permissions (:permissions identity)
                     user-name (:user/name identity)]
                 (condp = request-method
                   :get (read-thread? datomic permissions thread-id user-name)
                   :put (read-thread? datomic permissions thread-id user-name)
                   false)))

   :put! (fn [{{:keys [add-watcher remove-watcher]} :edn req :request}]
           (when-let [user (d/query datomic
                                    '{:find [?u .]
                                      :in [$ ?name]
                                      :where [[?u :user/name ?name]]}
                                    (get-in req [:identity :user/name]))]
             (when add-watcher
               (d/transact datomic
                           [[:db/add thread-id :thread/watchers user]]))
             (when remove-watcher
               (d/transact datomic
                           [[:db/retract thread-id :thread/watchers user]]))))
   :handle-created (fn [_]
                     {:status "ok"})
   :handle-ok (fn [_]
                (-> (d/pull datomic
                            '[:*
                              {:thread/comments
                               [:*
                                {:comment/format [:db/ident]}
                                {:comment/posted-by [:user/name :user/email]}]}]
                            thread-id)
                    (update-in [:thread/comments]
                               (partial map-indexed #(assoc %2  :comment/no (inc %1))))))))

(defn comments-resource [{:keys [datomic socketapp]} thread-id from to]
  (liberator/resource
   :available-media-types ["application/edn" "application/json"]
   :allowed-methods [:get :post]
   :malformed? #(parse-request % {:comment/content [[v/required]
                                                    [v/max-count 4000]]
                                  :comment/format  [[v/member [:comment.format/plain
                                                               :comment.format/markdown
                                                               :comment.format/voice
                                                               "comment.format/plain"
                                                               "comment.format/markdown"
                                                               "comment.format/voice"]]]})

   :allowed? (fn [{{:keys [request-method identity]} :request}]
               (let [permissions (:permissions identity)
                     user-name (:user/name identity)]
                 (condp = request-method
                   :get (read-thread? datomic permissions thread-id user-name)
                   :post (and (:create-comment permissions)
                              (read-thread? datomic permissions thread-id user-name))
                   false)))

   :processable? (fn [ctx]
                   (if (#{:put :post} (get-in ctx [:request :request-method]))
                     (if-let [resnum (d/query datomic
                                           '{:find [(count ?comment) .]
                                             :in [$ ?thread]
                                             :where [[?thread :thread/comments ?comment]]}
                                           thread-id)]
                       (if (< resnum 1000) {:thread/resnum resnum} false)
                       false)
                     true))
   :post! (fn [{comment :edn req :request resnum :thread/resnum}]
            (let [user (d/query datomic
                                '{:find [?u .]
                                  :in [$ ?name]
                                  :where [[?u :user/name ?name]]}
                                (get-in req [:identity :user/name]))
                  board-name (d/query datomic
                                       '{:find [?bname .]
                                         :in [$ ?t]
                                         :where [[?b :board/name ?bname]
                                                 [?b :board/threads ?t]]}
                                       thread-id)
                  now (Date.)]
              (d/transact
               datomic
               (concat [[:db/add thread-id :thread/comments #db/id[:db.part/user -1]]
                        {:db/id #db/id[:db.part/user -1]
                         :comment/posted-at now
                         :comment/posted-by user
                         :comment/format (-> comment
                                             (get :comment/format :comment.format/plain)
                                             keyword)
                         :comment/content (:comment/content comment)}]
                       (when-not (:comment/sage? comment)
                         [{:db/id thread-id :thread/last-updated now}])))
              (broadcast-message
               socketapp
               [:update-thread {:db/id thread-id
                                :thread/last-updated now
                                :thread/resnum (inc resnum)
                                :board/name board-name}])
              (when-let [watchers (not-empty (->> (d/pull datomic
                                                          '[{:thread/watchers
                                                             [:user/name :user/email]}]
                                                          thread-id)
                                                  :thread/watchers
                                                  (apply hash-set)))]
                (multicast-message
                 socketapp
                 [:notify {:thread/id thread-id
                           :board/name (d/query datomic
                                                '{:find [?bname .]
                                                  :in [$ ?t]
                                                  :where [[?b :board/name ?bname]
                                                          [?b :board/threads ?t]]}
                                                thread-id)
                           :comment/no (inc resnum)
                           :comment/posted-at now
                           :comment/posted-by (d/pull datomic '[:user/name :user/email] user)
                           :comment/content (:comment/content comment)
                           :comment/format (get :comment/format comment :comment.format/plain)}]
                 watchers))))
   :handle-ok (fn [_]
                (->> (d/pull datomic
                             '[{:thread/comments
                                [:*
                                 {:comment/format [:db/ident]
                                  :comment/posted-by [:user/name :user/email]
                                  :comment/reactions
                                  [{:comment-reaction/reaction [:reaction/label]
                                    :comment-reaction/reaction-by [:user/name :user/email]}]}]}]
                             thread-id)
                     :thread/comments
                     (map-indexed #(assoc %2 :comment/no (inc %1)))
                     (drop (dec from))
                     vec))))

(defn comment-resource
  "Returns a resource that react to a comment"
  [{:keys [datomic socketapp]} thread-id comment-no]
  {:pre [(integer? thread-id) (integer? comment-no)]}
  (liberator/resource
   :available-media-types ["application/edn" "application-json"]
   :allowed-methods [:post]
   :malformed? #(parse-request % {:reaction/name [[v/required]]})
   :authorized? (fn [ctx]
                  (if-let [identity (get-in ctx [:request :identity])]
                    {::identity identity}
                    false))

   :allowed? (fn [{{:keys [request-method identity]} :request}]
               (let [permissions (:permissions identity)]
                 (condp = request-method
                   :post (and (:create-comment permissions)
                              (read-thread? datomic permissions thread-id (:user/name identity)))
                   false)))

   :post! (fn [{comment-reaction :edn identity ::identity}]
            (let [user (d/query datomic
                                '{:find [?u .]
                                  :in [$ ?name]
                                  :where [[?u :user/name ?name]]}
                                (:user/name identity))
                  board-name (d/query datomic
                                      '{:find [?bname .]
                                        :in [$ ?t]
                                        :where [[?b :board/name ?bname]
                                                [?b :board/threads ?t]]}
                                      thread-id)
                  reaction (d/query datomic
                                    '{:find [?r .]
                                      :in [$ ?r-name]
                                      :where [[?r :reaction/name ?r-name]]}
                                    (:reaction/name comment-reaction))
                  comments (->> (d/pull datomic
                                        '[{:thread/comments [:db/id]}]
                                        thread-id)
                                :thread/comments)
                  comment-id (->> comments
                                  (drop (dec comment-no))
                                  (take 1)
                                  first
                                  :db/id)
                  now (Date.)
                  tempid (d/tempid :db.part/user)]
              (d/transact datomic
                          [{:db/id tempid
                            :comment-reaction/reaction-at now
                            :comment-reaction/reaction-by user
                            :comment-reaction/reaction reaction}
                           [:db/add comment-id
                            :comment/reactions tempid]])
              (broadcast-message
               socketapp
               [:update-thread {:db/id thread-id
                                :thread/last-updated now
                                :thread/resnum (count comments)
                                :comments/from comment-no
                                :comments/to   comment-no
                                :board/name board-name}])))))

(defn voices-resource [{:keys [datomic]} thread-id]
  (liberator/resource
   :available-media-types ["application/edn" "application-json"]
   :allowed-methods [:post]
   :malformed? (fn [ctx]
                 (let [content-type (get-in ctx [:request :headers "content-type"])]
                   (case content-type
                     "audio/webm" [false {::media-type :audio/webm}]
                     "audio/ogg"  [false {::media-type :audio/ogg}]
                     "audio/wav"  [false {::media-type :audio/wav}]
                     true)))

   :allowed? (fn [{{:keys [request-method identity]} :request}]
               (let [permissions (:permissions identity)]
                 (condp = request-method
                   :post (and (:create-comment permissions)
                              (read-thread? datomic permissions thread-id (:user/name identity)))
                   false)))

   :post! (fn [ctx]
            (let [body-stream (get-in ctx [:request :body])
                  filename (str (.toString (UUID/randomUUID))
                                (case (::media-type ctx)
                                  :audio/webm ".webm"
                                  :audio/ogg  ".ogg"
                                  :audio/wav  ".wav"))
                  path (Paths/get "voices"
                                  (into-array String [(str thread-id) filename]))]
              (Files/createDirectories (.getParent path)
                                       (make-array FileAttribute 0))
              (Files/copy body-stream path
                          (make-array CopyOption 0))
              {::filename filename}))
   :handle-created (fn [ctx]
                     {:comment/content (str thread-id "/" (::filename ctx))})))

(defn find-article-by-name [datomic article-name]
  (d/query
   datomic
   '{:find [[?article]]
     :in [$ ?article-name]
     :where [[?article :article/name ?article-name]]}
   article-name))

(defn articles-resource [{:keys [datomic]}]
  (liberator/resource
   :available-media-types ["application/edn" "application/json"]
   :allowed-methods [:get :post]
   :malformed? #(parse-request %)
   :allowed? (fn [{{:keys [request-method identity]} :request article :edn}]
               (let [permissions (:permissions identity)]
                 (condp = request-method
                   :get (and (:list-articles permissions)
                             (or (:list-any-threads permissions)
                                 (:list-threads permissions)))
                   :post (and (:create-article permissions)
                              (read-thread? datomic permissions (:article/thread article) (:user/name identity)))
                   false)))

   :post-to-existing? (fn [{{article-name :article/name} :edn :as ctx}]
                        (not
                         (or
                          (#{:get} (get-in ctx [:request :request-method]))
                          (and (#{:post} (get-in ctx [:request :request-method]))
                               (find-article-by-name datomic article-name)))))

   ;; Only :post-to-existing? = false pattern.
   :put-to-existing? (fn [ctx]
                       (#{:post} (get-in ctx [:request :request-method])))
   :conflict? (fn [ctx]
                (#{:post} (get-in ctx [:request :request-method])))

   :post! (fn [{article :edn req :request}]
            (let [article-id (d/tempid :db.part/user)
                  tempids (-> (d/transact datomic
                                          (apply concat [{:db/id article-id
                                                          :article/name (:article/name article)
                                                          :article/curator [:user/name (get-in article [:article/curator :user/name])]
                                                          :article/thread (:article/thread article)}]
                                                 (for [block (:article/blocks article)]
                                                   (let [tempid (d/tempid :db.part/user)]
                                                     [[:db/add article-id :article/blocks tempid]
                                                      {:db/id tempid
                                                       :curating-block/content (:curating-block/content block)
                                                       :curating-block/format  (:curating-block/format  block)
                                                       :curating-block/posted-at (:curating-block/posted-at block)
                                                       :curating-block/posted-by [:user/name (get-in block [:curating-block/posted-by :user/name])]}]))))
                              :tempids)]
              {:db/id (d/resolve-tempid datomic tempids article-id)}))

   :handle-created (fn [ctx]
                     {:db/id (:db/id ctx)})
   :handle-ok (fn [{{:keys [identity]} :request}]
                (let [permissions (:permissions identity)
                      user-name (:user/name identity)]
                  (if (:list-any-threads permissions)
                    (d/query datomic
                             '{:find [[(pull ?a [:*]) ...]]
                               :where [[?a :article/name]]})
                    (let [opens (d/query datomic
                                         '{:find [[(pull ?a [:*]) ...]]
                                           :where [[?a :article/thread ?th]
                                                   [?th :thread/open? true]]})
                          createds (d/query datomic
                                            '{:find [[(pull ?a [:*]) ...]]
                                              :in [$ ?u-name]
                                              :where [[?a :article/thread ?th]
                                                      [?th :thread/posted-by ?u]
                                                      [?u :user/name ?u-name]]}
                                            user-name)]
                      (concat opens createds)))))))

(defn read-article? [datomic permissions article-id user-name]
  (and (:list-articles permissions)
       (or (:list-any-threads permissions)
           (and (:list-thread permissions)
                (or (d/query datomic
                             '{:find [?a .]
                               :in [$ ?a]
                               :where [[?a :article/thread ?th]
                                       [?th :thread/open? true]]}
                             article-id)
                    (d/query datomic
                             '{:find [?a .]
                               :in [$ ?a ?u-name]
                               :where [[?a :article/thread ?th]
                                       [?th :thread/posted-by ?u]
                                       [?u :user/name ?u-name]]}
                             article-id user-name))))))

(defn article-resource [{:keys [datomic]} article-id]
  (liberator/resource
   :available-media-types ["application/edn" "application/json"]
   :allowed-methods [:get :put]
   :malformed? #(parse-request %)
   :allowed? (fn [{{:keys [request-method identity]} :request}]
               (let [permissions (:permissions identity)
                     user-name (:user/name identity)]
                 (condp = request-method
                   :get (read-article? datomic permissions article-id user-name)
                   :put (and (:modify-articles permissions)
                             (read-article? datomic permissions article-id user-name))
                   false)))

   :put! (fn [{article :edn req :request}]
           (let [retract-transaction (->> (:article/blocks
                                           (d/pull datomic '[:article/blocks] article-id))
                                          (map (fn [{id :db/id}]
                                                 [:db/retract article-id :article/blocks id])))]
             (d/transact datomic
                         (apply
                          concat retract-transaction
                          [{:db/id article-id
                            :article/name (:article/name article)
                            :article/curator [:user/name (get-in article [:article/curator :user/name])]}]
                          (for [block (:article/blocks article)]
                            (let [tempid (d/tempid :db.part/user)]
                              [[:db/add article-id :article/blocks tempid]
                               {:db/id tempid
                                :curating-block/content (:curating-block/content block)
                                :curating-block/format  (:curating-block/format  block)
                                :curating-block/posted-at (:curating-block/posted-at block)
                                :curating-block/posted-by [:user/name (get-in block [:curating-block/posted-by :user/name])]}]))))))

   :handle-ok (fn [_]
                (d/pull datomic
                        '[:*
                          {:article/curator [:user/name :user/email]}
                          {:article/blocks [:curating-block/posted-at
                                            :curating-block/content
                                            {:curating-block/format [:db/ident]}
                                            {:curating-block/posted-by [:user/name :user/email]}]}
                          {:article/thread [:*]}]
                        article-id))))

(defn token-resource [{:keys [datomic token]}]
  (liberator/resource
   :available-media-types ["application/edn" "application/json"]
   :allowed-methods [:post]
   :malformed? (fn [ctx]
                 (if-let [identity (get-in ctx [:request :identity])]
                   [false {::identity identity}]
                   (if-let [code (get-in ctx [:request :params :code])]
                     (if-let [identity (d/query datomic
                                                '{:find [(pull ?s [:user/name :user/email]) .]
                                                  :in [$ ?token]
                                                  :where [[?s :user/token ?token]]} code)]
                       [false {::identity identity}]
                       {:message "code is invalid."})
                     {:message "code is required."})))

   :allowed? (fn [{{:keys [request-method identity]} :request}]
               (let [permissions (:permissions identity)]
                 (condp = request-method
                   :post (:create-token permissions)
                   false)))

   :post! (fn [{identity ::identity}]
            (let [access-token (token/new-token token identity)]
              {::post-response (merge identity
                                      {:token-type "bearer"
                                       :access-token access-token})}))
   :handle-created (fn [ctx]
                     (::post-response ctx))))

(defn reactions-resource [{:keys [datomic]}]
  (liberator/resource
   :available-media-types ["application/edn" "application/json"]
   :allowed-methods [:get]
   :allowed? (fn [{{:keys [request-method identity]} :request}]
               (let [permissions (:permissions identity)]
                 (condp = request-method
                   :get (:list-reactions permissions)
                   false)))

   :handle-ok (fn [_]
                (d/query datomic
                         '{:find [[(pull ?r [:*]) ...]]
                           :in [$]
                           :where [[?r :reaction/name]]}))))

(defn api-endpoint [{:keys [datomic token socketapp tag user] :as config}]
  (context "/api" []
   (ANY "/token" []  (token-resource config))
   (ANY "/boards" [] (boards-resource config))
   (ANY "/board/:board-name" [board-name]
     (board-resource config board-name))
   (ANY "/board/:board-name/threads" [board-name]
     (threads-resource config board-name))
   (ANY "/thread/:thread-id" [thread-id]
     (thread-resource config (Long/parseLong thread-id)))
   (ANY "/thread/:thread-id/comments" [thread-id]
     (comments-resource config (Long/parseLong thread-id) 1 nil))
   (ANY ["/thread/:thread-id/comments/:comment-range" :comment-range #"\d+\-(\d+)?"]
       [thread-id comment-range]
     (let [[_ from to] (re-matches #"(\d+)\-(\d+)?" comment-range)]
       (comments-resource config
                          (Long/parseLong thread-id)
                          (when from (Long/parseLong from))
                          (when to (Long/parseLong to)))))
   (ANY "/thread/:thread-id/voices" [thread-id]
     (voices-resource config (Long/parseLong thread-id)))
   (ANY "/thread/:thread-id/comment/:comment-no"
       [thread-id :<< as-int comment-no :<< as-int]
     (comment-resource config thread-id comment-no))
   (ANY "/thread/:thread-id/tags" [thread-id]
     (tag/thread-list-resource config (Long/parseLong thread-id)))
   (ANY "/thread/:thread-id/tag/:tag-id" [thread-id tag-id]
     (tag/thread-resource config (Long/parseLong thread-id) (Long/parseLong tag-id)))
   (ANY "/articles" []
     (articles-resource config))
   (ANY "/article/:article-id" [article-id]
     (article-resource config (Long/parseLong article-id)))
   (ANY "/users" [] (user/list-resource user))
   (ANY "/user/:user-name" [user-name]
     (user/entry-resource user user-name))

   (ANY "/reactions" [] (reactions-resource config))

   (ANY "/tags" [] (tag/list-resource tag))
   (ANY "/tag/:tag-id" [tag-id]
     (tag/entry-resource tag (Long/parseLong tag-id)))))
