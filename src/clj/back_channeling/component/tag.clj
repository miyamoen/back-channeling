(ns back-channeling.component.tag
  (:require [clojure.tools.logging :as log]
            [liberator.core :as liberator]
            [bouncer.validators :as v]
            [com.stuartsierra.component :as component]
            (back-channeling [util :refer [parse-request]])
            (back-channeling.component [datomic :as d]
                                       [user :as user])))

(defn save-tag [datomic tag]
  (let [tag-id (d/tempid :db.part/user)
        tempids (-> (d/transact datomic
                                [{:db/id tag-id
                                  :tag/name (:tag/name tag)
                                  :tag/description (or (:tag/description tag) "")
                                  :tag/color (-> tag
                                                 (get :tag/color :tag.color/white)
                                                 keyword)
                                  :tag/priority (or (:tag/priority tag) 0)}])
                    :tempids)]
    [tempids tag-id]))

(defn find-tag [datomic tag-id]
  (when tag-id
    (d/query datomic
             '{:find [(pull ?t [:*]) .]
               :in [$ ?t]
               :where [[?t :tag/name]]}
             tag-id)))

(defn list-resource [{:keys [datomic]}]
  (liberator/resource
   :available-media-types ["application/edn" "application/json"]
   :allowed-methods [:get :post]
   :malformed? #(parse-request % {:tag/name [[v/required]
                                             [v/max-count 255]]
                                  :tag/color [[v/member [:tag.color/white
                                                         :tag.color/black
                                                         :tag.color/grey
                                                         :tag.color/yellow
                                                         :tag.color/orange
                                                         :tag.color/green
                                                         :tag.color/red
                                                         :tag.color/blue
                                                         :tag.color/pink
                                                         :tag.color/purple
                                                         :tag.color/brown
                                                         "tag.color/white"
                                                         "tag.color/black"
                                                         "tag.color/grey"
                                                         "tag.color/yellow"
                                                         "tag.color/orange"
                                                         "tag.color/green"
                                                         "tag.color/red"
                                                         "tag.color/blue"
                                                         "tag.color/pink"
                                                         "tag.color/purple"
                                                         "tag.color/brown"]]]})

   :allowed? (fn [{{:keys [request-method identity]} :request}]
               (let [permissions (:permissions identity)]
                 (condp = request-method
                   :get (:list-tags permissions)
                   :post (:create-tag permissions)
                   false)))

   :post! (fn [{tag :edn req :request}]
            (let [[tempids tag-id] (save-tag datomic tag)]
              {:db/id (d/resolve-tempid datomic tempids tag-id)}))

   :handle-created (fn [ctx]
                     {:db/id (:db/id ctx)})

   :handle-ok (fn [_]
                (d/query datomic
                         '{:find [[(pull ?tag [:*]) ...]]
                           :where [[?tag :tag/name]]}))))

(defn entry-resource [{:keys [datomic]} tag-id]
  (liberator/resource
   :available-media-types ["application/edn" "application/json"]
   :allowed-methods [:get :put]
   :malformed? #(parse-request %)
   :allowed? (fn [{{:keys [request-method identity]} :request}]
               (let [permissions (:permissions identity)]
                 (condp = request-method
                   :get (:list-tags permissions)
                   :put (or (:modify-any-tags permissions) (:modify-tags permissions))
                   false)))

   :exists? (fn [ctx]
              (when-let [tag (d/pull datomic '[:*] tag-id)]
                {::tag tag}))

   :new? (fn [_] false)
   :put! (fn [{old ::tag new :edn}]
           (d/transact datomic
                       (filter some? [(when-let [name (:tag/name new)]               [:db/add tag-id :tag/name name])
                                      (when-let [description (:tag/description new)] [:db/add tag-id :tag/description description])
                                      (when-let [color (:tag/color new)]             [:db/add tag-id :tag/color color])
                                      (when-let [priority (:tag/priority new)]       [:db/add tag-id :tag/priority priority])])))

   :handle-ok (fn [{tag ::tag}]
                tag)))

(defn thread-list-resource [{:keys [datomic]} thread-id]
  (liberator/resource
   :available-media-types ["application/edn" "application/json"]
   :allowed-methods [:post]
   :malformed? #(parse-request % {:db/id [[v/required]]})
   :allowed? (fn [{{:keys [request-method identity]} :request}]
               (let [permissions (:permissions identity)]
                 (condp = request-method
                   :post (or (:modify-any-threads permissions) (:modify-threads permissions))
                   false)))

   :exists? (fn [ctx]
              (let [tag-id (get-in ctx [:edn :db/id])]
                (when-not (d/query datomic
                                   '{:find [?t .]
                                     :in [$ ?th ?t]
                                     :where [[?th :thread/tags ?t]]}
                                   thread-id tag-id)
                  [false {::tag-id tag-id}])))

   :post! (fn [{tag-id ::tag-id}]
            (d/transact datomic [[:db/add thread-id :thread/tags tag-id]]))

   :post-to-existing? (fn [_] false)))

(defn thread-resource [{:keys [datomic]} thread-id tag-id]
  (liberator/resource
   :available-media-types ["application/edn" "application/json"]
   :allowed-methods [:delete]
   :allowed? (fn [{{:keys [request-method identity]} :request}]
               (let [permissions (:permissions identity)]
                 (condp = request-method
                   :delete (or (:modify-any-threads permissions) (:modify-threads permissions))
                   false)))

   :exists? (fn [_]
               (d/query datomic
                        '{:find [?t .]
                          :in [$ ?th ?t]
                          :where [[?th :thread/tags ?t]]}
                        thread-id tag-id))
   :delete! (fn [_]
              (d/transact datomic [[:db/retract thread-id :thread/tags tag-id]]))))

(defrecord Tag []
  component/Lifecycle

  (start [component]
    component)

  (stop [component]
    component))

(defn tag-component [& options]
  (map->Tag options))
