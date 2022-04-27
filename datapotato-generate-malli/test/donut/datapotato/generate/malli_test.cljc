(ns donut.datapotato.generate.malli-test
  (:require
   #?(:clj [clojure.test :refer [deftest is use-fixtures testing]]
      :cljs [cljs.test :include-macros true :refer [deftest is use-fixtures testing]])
   [donut.datapotato.test-data :as td]
   [donut.datapotato.core :as dd]
   [donut.datapotato.generate.malli :as dgm]
   [malli.generator :as mg]))

;;---
;; schemas
;;---

(def ID
  [:and {:gen/gen td/monotonic-id-gen} pos-int?])

(def User
  [:map
   [:id ID]
   [:username [:enum "Luigi"]]])

(def Todo
  [:map
   [:id ID]
   [:todo-title string?]
   [:created-by-id ID]
   [:updated-by-id ID]])


(def TodoList
  [:map
   [:id ID]
   [:created-by-id ID]
   [:updated-by-id ID]])

(def TodoListWatch
  [:map
   [:id ID]
   [:todo-list-id ID]
   [:watcher-id ID]])

;; In THE REAL WORLD todo-list would probably have a project-id,
;; rather than project having some coll of :todo-list-ids
(def Project
  [:map
   [:id ID]
   [:todo-list-ids [:vector ID]]
   [:created-by-id ID]
   [:updated-by-id ID]])

(def schema
  (-> td/schema
      (assoc-in [:user :generate :schema] User)
      (assoc-in [:todo :generate :schema] Todo)
      (assoc-in [:todo-list :generate :schema] TodoList)
      (assoc-in [:todo-list-watch :generate :schema] TodoListWatch)
      (assoc-in [:project :generate :schema] Project)))

(def cycle-schema
  (-> td/cycle-schema
      (assoc-in [:user :generate :schema] User)
      (assoc-in [:todo :generate :schema] Todo)
      (assoc-in [:todo-list :generate :schema] TodoList)))

(def TopicCategory [:map [:id ID]])
(def Topic
  [:map
   [:id ID]
   [:topic-category-id ID]])

(def Watch
  [:map
   [:id ID]
   [:watched-id ID]])

(def polymorphic-schema
  (-> td/polymorphic-schema
      (assoc-in [:topic-category-id :generate :schema] TopicCategory)
      (assoc-in [:topic :generate :schema] Todo)
      (assoc-in [:watch :generate :schema] Watch)))


;;---
;; test helpers
;;---

(def gen-data-db (atom []))
(def gen-data-cycle-db (atom []))

(defn reset-dbs [f]
  (reset! gen-data-db [])
  (reset! gen-data-cycle-db [])
  (f))

(use-fixtures :each reset-dbs)

(defn ids-present?
  [generated]
  (every? pos-int? (map :id (vals generated))))

(defn only-has-ents?
  [generated ent-names]
  (= (set (keys generated))
     (set ent-names)))

(defn ids-match?
  "Reference attr vals equal their referent"
  [generated matches]
  (every? (fn [[ent id-path-map]]
            (every? (fn [[attr id-path-or-paths]]
                      (if (vector? (first id-path-or-paths))
                        (= (set (map (fn [id-path] (get-in generated id-path)) id-path-or-paths))
                           (set (get-in generated [ent attr])))
                        (= (get-in generated id-path-or-paths)
                           (get-in generated [ent attr]))))
                    id-path-map))
          matches))

(deftest test-spec-gen
  (let [gen (dd/generate-attr-map
             {:schema schema
              :generator mg/generate}
             {:todo-list [[1]]})]
    (is (td/submap? {:u0 {:username "Luigi"}}
                    gen))
    (is (ids-present? gen))
    (is (ids-match? gen
                    {:tl0 {:created-by-id [:u0 :id]
                           :updated-by-id [:u0 :id]}}))
    (is (only-has-ents? gen #{:tl0 :u0}))))

(deftest test-spec-gen-nested
  (let [gen (dgm/generate-attr-map {:schema schema} {:project [[:_ {:refs {:todo-list-ids 3}}]]})]
    (is (td/submap? {:u0 {:username "Luigi"}} gen))
    (is (ids-present? gen))
    (is (ids-match? gen
                    {:tl0 {:created-by-id [:u0 :id]
                           :updated-by-id [:u0 :id]}
                     :tl1 {:created-by-id [:u0 :id]
                           :updated-by-id [:u0 :id]}
                     :tl2 {:created-by-id [:u0 :id]
                           :updated-by-id [:u0 :id]}
                     :p0  {:created-by-id [:u0 :id]
                           :updated-by-id [:u0 :id]
                           :todo-list-ids [[:tl0 :id]
                                           [:tl1 :id]
                                           [:tl2 :id]]}}))
    (is (only-has-ents? gen #{:tl0 :tl1 :tl2 :u0 :p0}))))

(deftest test-spec-gen-manual-attr
  (testing "Manual attribute setting for non-reference field"
    (let [gen (dgm/generate-attr-map {:schema schema} {:todo [[:_ {:generate {:todo-title "pet the dog"}}]]})]
      (is (td/submap? {:u0 {:username "Luigi"}
                       :t0 {:todo-title "pet the dog"}}
                      gen))
      (is (ids-present? gen))
      (is (ids-match? gen
                      {:tl0 {:created-by-id [:u0 :id]
                             :updated-by-id [:u0 :id]}
                       :t0  {:created-by-id [:u0 :id]
                             :updated-by-id [:u0 :id]
                             :todo-list-id  [:tl0 :id]}}))
      (is (only-has-ents? gen #{:tl0 :t0 :u0}))))

  (testing "Manual attribute setting for reference field"
    (let [gen (dgm/generate-attr-map {:schema schema} {:todo [[:_ {:generate {:created-by-id 1}}]]})]
      (is (td/submap? {:u0 {:username "Luigi"}
                       :t0 {:created-by-id 1}}
                      gen))
      (is (ids-present? gen))
      (is (ids-match? gen
                      {:tl0 {:created-by-id [:u0 :id]
                             :updated-by-id [:u0 :id]}
                       :t0  {:updated-by-id [:u0 :id]
                             :todo-list-id  [:tl0 :id]}}))
      (is (only-has-ents? gen #{:tl0 :t0 :u0})))))

(deftest test-spec-gen-omit
  (testing "Ref not created and attr is not present when omitted"
    (let [gen (dgm/generate-attr-map {:schema schema} {:todo-list [[:_ {:refs {:created-by-id ::dd/omit
                                                                               :updated-by-id ::dd/omit}}]]})]
      (is (ids-present? gen))
      (is (only-has-ents? gen #{:tl0}))
      (is (= [:id] (keys (:tl0 gen))))))

  (testing "Ref is created when at least 1 field references it, but omitted attrs are still not present"
    (let [gen (dgm/generate-attr-map {:schema schema} {:todo-list [[:_ {:refs {:updated-by-id ::dd/omit}}]]})]
      (is (td/submap? {:u0 {:username "Luigi"}} gen))
      (is (ids-present? gen))
      (is (ids-match? gen
                      {:tl0 {:created-by-id [:u0 :id]}}))
      (is (only-has-ents? gen #{:tl0 :u0}))
      (is (= [:id :created-by-id] (keys (:tl0 gen))))))

  (testing "Overwriting value of omitted ref with custom value"
    (let [gen (dgm/generate-attr-map {:schema schema} {:todo-list [[:_ {:refs     {:updated-by-id ::dd/omit}
                                                                        :generate {:updated-by-id 42}}]]})]
      (is (ids-present? gen))
      (is (= 42 (-> gen :tl0 :updated-by-id)))))

  (testing "Overwriting value of omitted ref with nil"
    (let [gen (dgm/generate-attr-map {:schema schema} {:todo-list [[:_ {:refs     {:updated-by-id ::dd/omit}
                                                                        :generate {:updated-by-id nil}}]]})]
      (is (ids-present? gen))
      (is (= nil (-> gen :tl0 :updated-by-id))))))

(deftest overwriting
  (testing "Overwriting generated value with query map"
    (let [gen (dgm/generate-attr-map {:schema schema} {:todo-list [[:_ {:generate {:updated-by-id 42}}]]})]
      (is (ids-present? gen))
      (is (= 42 (-> gen :tl0 :updated-by-id)))))

  (testing "Overwriting generated value with query fn"
    (let [gen (dgm/generate-attr-map {:schema schema} {:todo-list [[:_ {:generate #(assoc % :updated-by-id :foo)}]]})]
      (is (ids-present? gen))
      (is (= :foo (-> gen :tl0 :updated-by-id)))))

  (testing "Overwriting generated value with schema map"
    (let [gen (dgm/generate-attr-map
               {:schema (assoc-in schema [:todo :generate :overwrites :todo-title] "schema title")}
               {:todo [[:_ {:generate #(assoc % :updated-by-id :foo)}]]})]
      (is (ids-present? gen))
      (is (= "schema title" (-> gen :t0 :todo-title)))))

  (testing "Overwriting generated value with schema fn"
    (let [gen (dgm/generate-attr-map
               {:schema (assoc-in schema [:todo :generate :overwrites] #(assoc % :todo-title "boop whooop"))}
               {:todo [[:_ {:generate #(assoc % :updated-by-id :foo)}]]})]
      (is (ids-present? gen))
      (is (= "boop whooop" (-> gen :t0 :todo-title))))))

(deftest test-idempotency
  (testing "Gen traversal won't replace already generated data with newly generated data"
    (let [gen-fn     #(dgm/generate % {:todo [[:t0 {:generate {:todo-title "pet the dog"}}]]})
          first-pass (gen-fn {:schema schema})]
      (is (= (:data first-pass)
             (:data (gen-fn first-pass)))))))


(deftest test-coll-relval-order
  (testing "When a relation has a `:coll` constraint, order its vals correctly")
  (let [gen (dgm/generate-attr-map
             {:schema schema}
             {:project [[:_ {:refs {:todo-list-ids 3}}]]})]
    (is (td/submap? {:u0 {:username "Luigi"}} gen))
    (is (ids-present? gen))
    (is (= (:todo-list-ids (:p0 gen))
           [(:id (:tl0 gen))
            (:id (:tl1 gen))
            (:id (:tl2 gen))]))
    (is (only-has-ents? gen #{:tl0 :tl1 :tl2 :u0 :p0}))))

(deftest test-sets-custom-relation-val
  (let [gen (dgm/generate-attr-map
             {:schema schema}
             {:user      [[:custom-user {:generate {:id 100}}]]
              :todo-list [[:custom-tl {:refs {:created-by-id :custom-user
                                              :updated-by-id :custom-user}}]]})]
    (is (td/submap? {:custom-user {:username "Luigi"
                                   :id        100}}
                    gen))
    (is (ids-present? gen))
    (is (ids-match? gen
                    {:custom-tl {:created-by-id [:custom-user :id]
                                 :updated-by-id [:custom-user :id]}}))
    (is (only-has-ents? gen #{:custom-tl :custom-user}))))

;; testing inserting
(defn insert
  [{:keys [data] :as db} {:keys [ent-name visit-key attrs]}]
  (swap! gen-data-db conj [(:ent-type attrs) ent-name (dgm/visit-key attrs)]))

(deftest test-insert-gen-data
  (-> (dgm/generate {:schema schema} {:todo [[1]]})
      (dd/visit-ents-once :inserted-data insert))

  ;; gen data is something like:
  ;; [[:user :u0 {:id 1 :username "Luigi"}]
  ;;  [:todo-list :tl0 {:id 2 :created-by-id 1 :updated-by-id 1}]
  ;;  [:todo :t0 {:id            5
  ;;              :todo-title    "write unit tests"
  ;;              :created-by-id 1
  ;;              :updated-by-id 1
  ;;              :todo-list-id  2}]]

  (let [gen-data @gen-data-db]
    (is (= (set (map #(take 2 %) gen-data))
           #{[:user :u0]
             [:todo-list :tl0]
             [:todo :t0]}))

    (let [ent-map (into {} (map #(vec (drop 1 %)) gen-data))]
      (is (td/submap? {:u0 {:username "Luigi"}
                       :t0 {:todo-title "write unit tests"}}
                      ent-map))
      (is (ids-present? ent-map))
      (is (ids-match? ent-map
                      {:tl0 {:created-by-id [:u0 :id]
                             :updated-by-id [:u0 :id]}
                       :t0  {:created-by-id [:u0 :id]
                             :updated-by-id [:u0 :id]
                             :todo-list-id  [:tl0 :id]}})))))

(deftest inserts-novel-data
  (testing "Given a db with a todo already added, next call adds a new
  todo that references the same todo list and user"
    (let [db1 (-> (dgm/generate {:schema schema} {:todo [[1]]})
                  (dd/visit-ents-once :inserted-data insert))]
      (-> (dgm/generate db1 {:todo [[1]]})
          (dd/visit-ents-once :inserted-data insert))

      (let [gen-data @gen-data-db]
        (is (= (set (map #(take 2 %) gen-data))
               #{[:user :u0]
                 [:todo-list :tl0]
                 [:todo :t0]
                 [:todo :t1]}))

        (let [ent-map (into {} (map #(vec (drop 1 %)) gen-data))]
          (is (td/submap? {:u0 {:username "Luigi"}
                           :t0 {:todo-title "write unit tests"}
                           :t1 {:todo-title "write unit tests"}}
                          ent-map))
          (is (ids-present? ent-map))
          (is (ids-match? ent-map
                          {:tl0 {:created-by-id [:u0 :id]
                                 :updated-by-id [:u0 :id]}
                           :t0  {:created-by-id [:u0 :id]
                                 :updated-by-id [:u0 :id]
                                 :todo-list-id  [:tl0 :id]}
                           :t1  {:created-by-id [:u0 :id]
                                 :updated-by-id [:u0 :id]
                                 :todo-list-id  [:tl0 :id]}})))))))

;;---
;; cycles
;;---

(defn insert-cycle
  [db {:keys [ent-name]}]
  (swap! gen-data-cycle-db conj ent-name)
  (dd/ent-attr db ent-name dgm/visit-key))

(deftest handle-cycles-with-constraints-and-reordering
  (testing "todo-list is inserted before todo because todo requires todo-list"
    (-> (dgm/generate {:schema cycle-schema} {:todo [[1]]})
        (dd/visit-ents :insert-cycle insert-cycle))
    (is (= @gen-data-cycle-db
           [:tl0 :t0]))))

(deftest handles-cycle-ids
  (testing "generate correctly sets foreign keys for cycles"
    (let [gen (dgm/generate-attr-map {:schema cycle-schema} {:todo [[1]]})]
      (is (ids-present? gen))
      (is (ids-match? gen
                      {:t0  {:todo-list-id [:tl0 :id]}
                       :tl0 {:first-todo-id [:t0 :id]}})))))

(deftest throws-exception-on-2nd-map-ent-attr-try
  (testing "insert-cycle fails because the schema contains a :required cycle"
    (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo
                             :cljs js/Object)
                          #"Can't sort ents: check for cycles in ent type relations"
                          (-> (dd/add-ents {:schema {:todo      {:spec        ::todo
                                                                 :relations   {:todo-list-id [:todo-list :id]}
                                                                 :prefix      :t}
                                                     :todo-list {:spec        ::todo-list
                                                                 :relations   {:first-todo-id [:todo :id]}
                                                                 :prefix      :tl}}}
                                           {:todo [[1]]})
                              (dd/visit-ents :insert-cycle insert-cycle))))))
