(ns donut.datapotato.generate.test-helpers
  (:require
   [clojure.data :as data]
   #?(:clj [clojure.test :refer [is use-fixtures testing]]
      :cljs [cljs.test :include-macros true :refer [is use-fixtures testing]])
   [donut.datapotato.core :as dd]))

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

(defn submap?
  "All vals in m1 are present in m2"
  [m1 m2]
  (nil? (first (data/diff m1 m2))))

;;---
;; tests
;;---

(defn test-generate
  [schema generator]
  (let [gen (dd/generate-attr-map
             {:schema    schema
              :generator generator}
             {:todo-list [[1]]})]
    (is (submap? {:u0 {:username "Luigi"}}
                 gen))
    (is (ids-present? gen))
    (is (ids-match? gen
                    {:tl0 {:created-by-id [:u0 :id]
                           :updated-by-id [:u0 :id]}}))
    (is (only-has-ents? gen #{:tl0 :u0}))))

(defn test-spec-gen-nested
  [schema generator]
  (let [gen (dd/generate-attr-map
             {:schema    schema
              :generator generator}
             {:project [[:_ {:refs {:todo-list-ids 3}}]]})]
    (is (submap? {:u0 {:username "Luigi"}} gen))
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

(defn test-spec-gen-manual-attr
  [schema generator]
  (testing "Manual attribute setting for non-reference field"
    (let [gen (dd/generate-attr-map
               {:schema    schema
                :generator generator}
               {:todo [[:_ {:generate {:todo-title "pet the dog"}}]]})]
      (is (submap? {:u0 {:username "Luigi"}
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
    (let [gen (dd/generate-attr-map
               {:schema    schema
                :generator generator}
               {:todo [[:_ {:generate {:created-by-id 1}}]]})]
      (is (submap? {:u0 {:username "Luigi"}
                    :t0 {:created-by-id 1}}
                   gen))
      (is (ids-present? gen))
      (is (ids-match? gen
                      {:tl0 {:created-by-id [:u0 :id]
                             :updated-by-id [:u0 :id]}
                       :t0  {:updated-by-id [:u0 :id]
                             :todo-list-id  [:tl0 :id]}}))
      (is (only-has-ents? gen #{:tl0 :t0 :u0})))))

(defn test-spec-gen-omit
  [schema generator]
  (testing "Ref not created and attr is not present when omitted"
    (let [gen (dd/generate-attr-map
               {:schema    schema
                :generator generator}
               {:todo-list [[:_ {:refs {:created-by-id ::dd/omit
                                        :updated-by-id ::dd/omit}}]]})]
      (is (ids-present? gen))
      (is (only-has-ents? gen #{:tl0}))
      (is (= [:id] (keys (:tl0 gen))))))

  (testing "Ref is created when at least 1 field references it, but omitted attrs are still not present"
    (let [gen (dd/generate-attr-map
               {:schema    schema
                :generator generator}
               {:todo-list [[:_ {:refs {:updated-by-id ::dd/omit}}]]})]
      (is (submap? {:u0 {:username "Luigi"}} gen))
      (is (ids-present? gen))
      (is (ids-match? gen
                      {:tl0 {:created-by-id [:u0 :id]}}))
      (is (only-has-ents? gen #{:tl0 :u0}))
      (is (= [:id :created-by-id] (keys (:tl0 gen))))))

  (testing "Overwriting value of omitted ref with custom value"
    (let [gen (dd/generate-attr-map
               {:schema    schema
                :generator generator}
               {:todo-list [[:_ {:refs     {:updated-by-id ::dd/omit}
                                 :generate {:updated-by-id 42}}]]})]
      (is (ids-present? gen))
      (is (= 42 (-> gen :tl0 :updated-by-id)))))

  (testing "Overwriting value of omitted ref with nil"
    (let [gen (dd/generate-attr-map
               {:schema    schema
                :generator generator}
               {:todo-list [[:_ {:refs     {:updated-by-id ::dd/omit}
                                 :generate {:updated-by-id nil}}]]})]
      (is (ids-present? gen))
      (is (= nil (-> gen :tl0 :updated-by-id))))))

(defn test-overwriting
  [schema generator]
  (testing "Overwriting generated value with query map"
    (let [gen (dd/generate-attr-map
               {:schema    schema
                :generator generator}
               {:todo-list [[:_ {:generate {:updated-by-id 42}}]]})]
      (is (ids-present? gen))
      (is (= 42 (-> gen :tl0 :updated-by-id)))))

  (testing "Overwriting generated value with query fn"
    (let [gen (dd/generate-attr-map
               {:schema    schema
                :generator generator}
               {:todo-list [[:_ {:generate #(assoc % :updated-by-id :foo)}]]})]
      (is (ids-present? gen))
      (is (= :foo (-> gen :tl0 :updated-by-id)))))

  (testing "Overwriting generated value with schema map"
    (let [gen (dd/generate-attr-map
               {:schema    (assoc-in schema [:todo :generate :overwrites :todo-title] "schema title")
                :generator generator}
               {:todo [[:_ {:generate #(assoc % :updated-by-id :foo)}]]})]
      (is (ids-present? gen))
      (is (= "schema title" (-> gen :t0 :todo-title)))))

  (testing "Overwriting generated value with schema fn"
    (let [gen (dd/generate-attr-map
               {:schema    (assoc-in schema [:todo :generate :overwrites] #(assoc % :todo-title "boop whooop"))
                :generator generator}
               {:todo [[:_ {:generate #(assoc % :updated-by-id :foo)}]]})]
      (is (ids-present? gen))
      (is (= "boop whooop" (-> gen :t0 :todo-title))))))

(defn test-idempotency
  [schema generator]
  (testing "Gen traversal won't replace already generated data with newly generated data"
    (let [gen-fn     #(dd/generate % {:todo [[:t0 {:generate {:todo-title "pet the dog"}}]]})
          first-pass (gen-fn {:schema schema
                              :generator generator})]
      (is (= (:data first-pass)
             (:data (gen-fn first-pass)))))))


(defn test-coll-relval-order
  [schema generator]
  (testing "When a relation has a `:coll` constraint, order its vals correctly")
  (let [gen (dd/generate-attr-map
             {:schema    schema
              :generator generator}
             {:project [[:_ {:refs {:todo-list-ids 3}}]]})]
    (is (submap? {:u0 {:username "Luigi"}} gen))
    (is (ids-present? gen))
    (is (= (:todo-list-ids (:p0 gen))
           [(:id (:tl0 gen))
            (:id (:tl1 gen))
            (:id (:tl2 gen))]))
    (is (only-has-ents? gen #{:tl0 :tl1 :tl2 :u0 :p0}))))

(defn test-sets-custom-relation-val
  [schema generator]
  (let [gen (dd/generate-attr-map
             {:schema    schema
              :generator generator}
             {:user      [[:custom-user {:generate {:id 100}}]]
              :todo-list [[:custom-tl {:refs {:created-by-id :custom-user
                                              :updated-by-id :custom-user}}]]})]
    (is (submap? {:custom-user {:username "Luigi"
                                :id       100}}
                 gen))
    (is (ids-present? gen))
    (is (ids-match? gen
                    {:custom-tl {:created-by-id [:custom-user :id]
                                 :updated-by-id [:custom-user :id]}}))
    (is (only-has-ents? gen #{:custom-tl :custom-user}))))

;; testing inserting
(defn insert
  [_db {:keys [ent-name attrs]}]
  (swap! gen-data-db conj [(:ent-type attrs) ent-name (:generate attrs)]))

(defn test-insert-gen-data
  [schema generator]
  (-> (dd/generate {:schema   schema
                    :generator generator} {:todo [[1]]})
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
      (is (submap? {:u0 {:username "Luigi"}
                    :t0 {:todo-title "write unit tests"}}
                   ent-map))
      (is (ids-present? ent-map))
      (is (ids-match? ent-map
                      {:tl0 {:created-by-id [:u0 :id]
                             :updated-by-id [:u0 :id]}
                       :t0  {:created-by-id [:u0 :id]
                             :updated-by-id [:u0 :id]
                             :todo-list-id  [:tl0 :id]}})))))

(defn test-inserts-novel-data
  [schema generator]
  (testing "Given a db with a todo already added, next call adds a new
  todo that references the same todo list and user"
    (let [db1 (-> (dd/generate {:schema   schema
                                :generator generator} {:todo [[1]]})
                  (dd/visit-ents-once :inserted-data insert))]
      (-> (dd/generate db1 {:todo [[1]]})
          (dd/visit-ents-once :inserted-data insert))

      (let [gen-data @gen-data-db]
        (is (= (set (map #(take 2 %) gen-data))
               #{[:user :u0]
                 [:todo-list :tl0]
                 [:todo :t0]
                 [:todo :t1]}))

        (let [ent-map (into {} (map #(vec (drop 1 %)) gen-data))]
          (is (submap? {:u0 {:username "Luigi"}
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
  (dd/ent-attr db ent-name :generate))

(defn test-handle-cycles-with-constraints-and-reordering
  [cycle-schema generator]
  (testing "todo-list is inserted before todo because todo requires todo-list"
    (-> (dd/generate
         {:schema   cycle-schema
          :generator generator}
         {:todo [[1]]})
        (dd/visit-ents :insert-cycle insert-cycle))
    (is (= @gen-data-cycle-db
           [:tl0 :t0]))))

(defn test-handles-cycle-ids
  [cycle-schema generator]
  (testing "generate correctly sets foreign keys for cycles"
    (let [gen (dd/generate-attr-map
               {:schema    cycle-schema
                :generator generator}
               {:todo [[1]]})]
      (is (ids-present? gen))
      (is (ids-match? gen
                      {:t0  {:todo-list-id [:tl0 :id]}
                       :tl0 {:first-todo-id [:t0 :id]}})))))

(defn test-throws-exception-on-2nd-map-ent-attr-try
  [cycle-schema generator]
  (testing "insert-cycle fails because the schema contains a :required cycle"
    (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo
                             :cljs js/Object)
                          #"Can't sort ents: check for cycles in ent type relations"
                          (-> (dd/add-ents {:schema {:todo      {:spec      ::todo
                                                                 :relations {:todo-list-id [:todo-list :id]}
                                                                 :prefix    :t}
                                                     :todo-list {:spec      ::todo-list
                                                                 :relations {:first-todo-id [:todo :id]}
                                                                 :prefix    :tl}}}
                                           {:todo [[1]]})
                              (dd/visit-ents :insert-cycle insert-cycle))))))


(defn run-generate-test-suite
  [{:keys [schema cycle-schema generator]}]
  (doseq [test [test-generate
                test-spec-gen-nested
                test-spec-gen-manual-attr
                test-spec-gen-omit
                test-overwriting
                test-idempotency
                test-coll-relval-order
                test-sets-custom-relation-val
                test-insert-gen-data
                test-inserts-novel-data]]
    (test schema generator))
  (doseq [test [test-handle-cycles-with-constraints-and-reordering
                test-handles-cycle-ids
                test-throws-exception-on-2nd-map-ent-attr-try]]
    (test cycle-schema generator)))
