(ns donut.datapotato.core-test
  (:require
   #?(:clj [clojure.test :refer [deftest is are use-fixtures testing]]
      :cljs [cljs.test :include-macros true :refer [deftest is are use-fixtures testing]])
   [clojure.spec.test.alpha :as stest]
   [donut.datapotato.test-data :as td]
   [donut.datapotato.core :as dc]
   [loom.graph :as lg]
   [loom.attr :as lat]))

(use-fixtures :each td/test-fixture)

(use-fixtures :once (fn [t] (stest/instrument) (t)))

;;---
;; test helpers
;;---

(defmacro is-graph=
  "Breaks graph equality test into comparisons on graph keys to
  pinpoint inequality more quickly"
  [g1 g2]
  (let [g1-sym 'returned
        g2-sym 'expected]
    `(let [~g1-sym ~g1
           ~g2-sym ~g2]
       (are [k] (= (k ~g1-sym) (k ~g2-sym))
         :nodeset
         :adj
         :in
         :attrs))))

(deftest test-relation-graph
  (is-graph= (dc/relation-graph td/schema)
             (lg/digraph [:project :todo-list]
                         [:project :user]
                         [:todo-list-watch :todo-list]
                         [:todo-list-watch :user]
                         [:todo :todo-list]
                         [:todo-list :user]
                         [:todo :user]
                         [:attachment :todo]
                         [:attachment :user])))

(defn strip-db
  [db]
  (dissoc db :relation-graph :types :type-order))

;;---
;; tests for helpers in donut.datapotato.core
;;---

(deftest test-normalize-query-term
  (testing "old and new query forms works"
    (is (= {:count    1
            :ent-name :_}
           (dc/normalize-query-term [1])
           (dc/normalize-query-term [:_])
           (dc/normalize-query-term {:count 1})
           (dc/normalize-query-term {:count    1
                                     :ent-name :_})))

    (is (= {:count    1
            :ent-name :bill}
           (dc/normalize-query-term [:bill])
           (dc/normalize-query-term {:count    1
                                     :ent-name :bill})
           (dc/normalize-query-term {:ent-name :bill})))

    (is (= {:count    5
            :ent-name :_}
           (dc/normalize-query-term [5])
           (dc/normalize-query-term {:count 5})))))

;;---
;; api tests
;;---

(deftest test-add-ents-empty
  (is-graph= {:schema td/schema
              :data   (lg/digraph)}
             (strip-db (dc/add-ents {:schema td/schema} {}))))

(deftest test-bound-relation-attr-name
  (is (= :t-bound-p-1
         (dc/bound-relation-attr-name (dc/add-ents {:schema td/schema} {}) :tl-bound-p-0 :todo 1))))

(deftest test-add-ents-relationless-ent
  (let [expected (-> (lg/digraph [:user :u1])
                     (lat/add-attr :user :type :ent-type)
                     (lat/add-attr :u1 :type :ent)
                     (lat/add-attr :u1 :index 0)
                     (lat/add-attr :u1 :query-term {:ent-name :u1 :count 1})
                     (lat/add-attr :u1 :ent-type :user))]
    (is-graph= expected (:data (dc/add-ents {:schema td/schema} {:user [[:u1]]})))
    (is-graph= expected (:data (dc/add-ents {:schema td/schema} {:user [{:ent-name :u1}]})))))

(deftest test-add-ents-mult-relationless-ents
  (let [expected (-> (lg/digraph [:user :u0] [:user :u1] [:user :u2])
                     (lat/add-attr :user :type :ent-type)
                     (lat/add-attr :u0 :type :ent)
                     (lat/add-attr :u0 :index 0)
                     (lat/add-attr :u0 :query-term {:ent-name :_ :count 3})
                     (lat/add-attr :u0 :ent-type :user)

                     (lat/add-attr :u1 :type :ent)
                     (lat/add-attr :u1 :index 1)
                     (lat/add-attr :u1 :query-term {:ent-name :_ :count 3})
                     (lat/add-attr :u1 :ent-type :user)

                     (lat/add-attr :u2 :type :ent)
                     (lat/add-attr :u2 :index 2)
                     (lat/add-attr :u2 :query-term {:ent-name :_ :count 3})
                     (lat/add-attr :u2 :ent-type :user))]
    (is-graph= expected (:data (strip-db (dc/add-ents {:schema td/schema} {:user [[3]]}))))
    (is-graph= expected (:data (strip-db (dc/add-ents {:schema td/schema} {:user [{:count 3}]}))))))

(deftest test-add-ents-one-level-relation
  (let [expected (-> (lg/digraph [:user :u0] [:todo-list :tl0] [:tl0 :u0])

                     (lat/add-attr :user :type :ent-type)
                     (lat/add-attr :u0 :type :ent)
                     (lat/add-attr :u0 :index 0)
                     (lat/add-attr :u0 :query-term {:ent-name :_})
                     (lat/add-attr :u0 :ent-type :user)

                     (lat/add-attr :todo-list :type :ent-type)
                     (lat/add-attr :tl0 :type :ent)
                     (lat/add-attr :tl0 :index 0)
                     (lat/add-attr :tl0 :ent-type :todo-list)
                     (lat/add-attr :tl0 :query-term {:ent-name :_ :count 1})

                     (lat/add-attr :tl0 :u0 :relation-attrs #{:created-by-id :updated-by-id}))]
    (is-graph= expected (:data (dc/add-ents {:schema td/schema} {:todo-list [[1]]})))
    (is-graph= expected (:data (dc/add-ents {:schema td/schema} {:todo-list [{:count 1}]})))))

(deftest test-add-ents-one-level-relation-with-omit
  (let [expected (-> (lg/digraph [:todo-list :tl0])

                     (lat/add-attr :todo-list :type :ent-type)
                     (lat/add-attr :tl0 :type :ent)
                     (lat/add-attr :tl0 :index 0)
                     (lat/add-attr :tl0 :ent-type :todo-list)
                     (lat/add-attr :tl0 :query-term {:ent-name :_
                                                     :count    1
                                                     :refs     {:created-by-id ::dc/omit
                                                                :updated-by-id ::dc/omit}}))]
    (is-graph= expected (:data (dc/add-ents {:schema td/schema}
                                            {:todo-list [[1 {:refs {:created-by-id ::dc/omit
                                                                    :updated-by-id ::dc/omit}}]]})))
    (is-graph= expected (:data (dc/add-ents {:schema td/schema}
                                            {:todo-list [{:count 1
                                                          :refs  {:created-by-id ::dc/omit
                                                                  :updated-by-id ::dc/omit}}]})))))

(deftest test-add-ents-mult-ents-w-extended-query
  (let [expected (-> (lg/digraph [:user :bloop]
                                 [:todo-list :tl0]
                                 [:todo-list :tl1]
                                 [:tl0 :bloop]
                                 [:tl1 :bloop])

                     (lat/add-attr :user :type :ent-type)
                     (lat/add-attr :bloop :type :ent)
                     (lat/add-attr :bloop :index 0)
                     (lat/add-attr :bloop :query-term {:ent-name :_})
                     (lat/add-attr :bloop :ent-type :user)

                     (lat/add-attr :todo-list :type :ent-type)
                     (lat/add-attr :tl0 :type :ent)
                     (lat/add-attr :tl0 :index 0)
                     (lat/add-attr :tl0 :ent-type :todo-list)
                     (lat/add-attr :tl0 :query-term {:ent-name :_
                                                     :count    2
                                                     :refs     {:created-by-id :bloop
                                                                :updated-by-id :bloop}})

                     (lat/add-attr :todo-list :type :ent-type)
                     (lat/add-attr :tl1 :type :ent)
                     (lat/add-attr :tl1 :index 1)
                     (lat/add-attr :tl1 :ent-type :todo-list)
                     (lat/add-attr :tl1 :query-term {:ent-name :_
                                                     :count    2
                                                     :refs     {:created-by-id :bloop,
                                                                :updated-by-id :bloop}})

                     (lat/add-attr :tl0 :bloop :relation-attrs #{:created-by-id :updated-by-id})
                     (lat/add-attr :tl1 :bloop :relation-attrs #{:created-by-id :updated-by-id}))]
    (is-graph= expected (-> {:schema td/schema}
                            (dc/add-ents {:todo-list [[2 {:refs {:created-by-id :bloop :updated-by-id :bloop}}]]})
                            :data))
    (is-graph= expected (-> {:schema td/schema}
                            (dc/add-ents {:todo-list [{:count 2
                                                       :refs {:created-by-id :bloop :updated-by-id :bloop}}]})
                            :data))))

(deftest test-add-ents-one-level-relation-custom-related
  (let [expected (-> (lg/digraph [:user :owner0] [:todo-list :tl0] [:tl0 :owner0])
                     (lat/add-attr :user :type :ent-type)
                     (lat/add-attr :owner0 :type :ent)
                     (lat/add-attr :owner0 :index 0)
                     (lat/add-attr :owner0 :query-term {:ent-name :_})
                     (lat/add-attr :owner0 :ent-type :user)
                     (lat/add-attr :todo-list :type :ent-type)
                     (lat/add-attr :tl0 :type :ent)
                     (lat/add-attr :tl0 :index 0)
                     (lat/add-attr :tl0 :ent-type :todo-list)
                     (lat/add-attr :tl0 :query-term {:ent-name :_
                                                     :count    1
                                                     :refs     {:created-by-id :owner0
                                                                :updated-by-id :owner0}})
                     (lat/add-attr :tl0 :owner0 :relation-attrs #{:updated-by-id :created-by-id}))]
    (is-graph= expected (-> {:schema td/schema}
                            (dc/add-ents {:todo-list [[:_ {:refs {:created-by-id :owner0
                                                                  :updated-by-id :owner0}}]]})
                            strip-db
                            :data))
    (is-graph= expected (-> {:schema td/schema}
                            (dc/add-ents {:todo-list [{:refs {:created-by-id :owner0
                                                              :updated-by-id :owner0}}]})
                            strip-db
                            :data))))

(deftest testadd-ents-two-level-coll-relation
  (testing "can specify how many ents to gen in a coll relationship"
    (let [expected (-> (lg/digraph [:user :u0]
                                   [:todo-list :tl0] [:todo-list :tl1]  [:tl0 :u0] [:tl1 :u0]
                                   [:project :p0] [:p0 :u0] [:p0 :tl0] [:p0 :tl1] [:p0 :u0])

                       (lat/add-attr :user :type :ent-type)
                       (lat/add-attr :u0 :type :ent)
                       (lat/add-attr :u0 :index 0)
                       (lat/add-attr :u0 :query-term {:ent-name :_})
                       (lat/add-attr :u0 :ent-type :user)

                       (lat/add-attr :project :type :ent-type)
                       (lat/add-attr :p0 :type :ent)
                       (lat/add-attr :p0 :index 0)
                       (lat/add-attr :p0 :query-term {:ent-name :_
                                                      :count    1
                                                      :refs     {:todo-list-ids 2}})
                       (lat/add-attr :p0 :ent-type :project)
                       (lat/add-attr :p0 :u0 :relation-attrs #{:created-by-id :updated-by-id})

                       (lat/add-attr :todo-list :type :ent-type)
                       (lat/add-attr :tl0 :type :ent)
                       (lat/add-attr :tl0 :index 0)
                       (lat/add-attr :tl0 :ent-type :todo-list)
                       (lat/add-attr :tl0 :query-term {:ent-name :_})

                       (lat/add-attr :todo-list :type :ent-type)
                       (lat/add-attr :tl1 :type :ent)
                       (lat/add-attr :tl1 :index 1)
                       (lat/add-attr :tl1 :ent-type :todo-list)
                       (lat/add-attr :tl1 :query-term {:ent-name :_})

                       (lat/add-attr :p0 :tl0 :relation-attrs #{:todo-list-ids})
                       (lat/add-attr :p0 :tl1 :relation-attrs #{:todo-list-ids})
                       (lat/add-attr :p0 :u0 :relation-attrs #{:created-by-id :updated-by-id})
                       (lat/add-attr :tl0 :u0 :relation-attrs #{:created-by-id :updated-by-id})
                       (lat/add-attr :tl1 :u0 :relation-attrs #{:created-by-id :updated-by-id}))]
      (is-graph= expected (-> {:schema td/schema}
                              (dc/add-ents {:project [[:_ {:refs {:todo-list-ids 2}}]]})
                              strip-db
                              :data))
      (is-graph= expected (-> {:schema td/schema}
                              (dc/add-ents {:project [{:refs {:todo-list-ids 2}}]})
                              strip-db
                              :data)))))

(deftest test-add-ents-two-level-coll-relation-names
  (testing "can specify names in a coll relationship"
    (is-graph= (-> (lg/digraph [:user :u0]
                               [:todo-list :mario] [:todo-list :luigi]  [:mario :u0] [:luigi :u0]
                               [:project :p0] [:p0 :u0] [:p0 :mario] [:p0 :luigi] [:p0 :u0])

                   (lat/add-attr :user :type :ent-type)
                   (lat/add-attr :u0 :type :ent)
                   (lat/add-attr :u0 :index 0)
                   (lat/add-attr :u0 :query-term {:ent-name :_})
                   (lat/add-attr :u0 :ent-type :user)

                   (lat/add-attr :project :type :ent-type)
                   (lat/add-attr :p0 :type :ent)
                   (lat/add-attr :p0 :index 0)
                   (lat/add-attr :p0 :query-term {:ent-name :_
                                                  :count    1
                                                  :refs     {:todo-list-ids [:mario :luigi]}})
                   (lat/add-attr :p0 :ent-type :project)
                   (lat/add-attr :p0 :u0 :relation-attrs #{:created-by-id :updated-by-id})

                   (lat/add-attr :todo-list :type :ent-type)
                   (lat/add-attr :mario :type :ent)
                   (lat/add-attr :mario :index 0)
                   (lat/add-attr :mario :ent-type :todo-list)
                   (lat/add-attr :mario :query-term {:ent-name :_})

                   (lat/add-attr :todo-list :type :ent-type)
                   (lat/add-attr :luigi :type :ent)
                   (lat/add-attr :luigi :index 1)
                   (lat/add-attr :luigi :ent-type :todo-list)
                   (lat/add-attr :luigi :query-term {:ent-name :_})

                   (lat/add-attr :p0 :mario :relation-attrs #{:todo-list-ids})
                   (lat/add-attr :p0 :luigi :relation-attrs #{:todo-list-ids})
                   (lat/add-attr :p0 :u0 :relation-attrs #{:created-by-id :updated-by-id})
                   (lat/add-attr :mario :u0 :relation-attrs #{:created-by-id :updated-by-id})
                   (lat/add-attr :luigi :u0 :relation-attrs #{:created-by-id :updated-by-id}))
               (:data (strip-db (dc/add-ents {:schema td/schema} {:project [[:_ {:refs {:todo-list-ids [:mario :luigi]}}]]}))))))

(deftest test-add-ents-one-level-relation-binding
  (let [expected  (-> (lg/digraph [:user :bloop] [:todo-list :tl0] [:tl0 :bloop])
                      (lat/add-attr :user :type :ent-type)
                      (lat/add-attr :bloop :type :ent)
                      (lat/add-attr :bloop :index 0)
                      (lat/add-attr :bloop :query-term {:ent-name :_ :bind {:user :bloop}})
                      (lat/add-attr :bloop :ent-type :user)
                      (lat/add-attr :todo-list :type :ent-type)
                      (lat/add-attr :tl0 :type :ent)
                      (lat/add-attr :tl0 :index 0)
                      (lat/add-attr :tl0 :ent-type :todo-list)
                      (lat/add-attr :tl0 :query-term {:count 1 :ent-name :_ :bind {:user :bloop}})
                      (lat/add-attr :tl0 :bloop :relation-attrs #{:created-by-id :updated-by-id}))]
    (is-graph= expected (:data (dc/add-ents {:schema td/schema} {:todo-list [[:_ {:bind {:user :bloop}}]]})))
    (is-graph= expected (:data (dc/add-ents {:schema td/schema} {:todo-list [{:bind {:user :bloop}}]})))))

(deftest test-add-ents-two-level-relation-binding
  (let [expected (-> (lg/digraph [:user :bloop]
                                 [:todo :t0]
                                 [:todo-list :tl-bound-t-0]
                                 [:t0 :bloop]
                                 [:t0 :tl-bound-t-0]
                                 [:tl-bound-t-0 :bloop])

                     (lat/add-attr :user :type :ent-type)
                     (lat/add-attr :bloop :type :ent)
                     (lat/add-attr :bloop :index 0)
                     (lat/add-attr :bloop :ent-type :user)
                     (lat/add-attr :bloop :query-term {:ent-name :_ :bind {:user :bloop}})

                     (lat/add-attr :todo :type :ent-type)
                     (lat/add-attr :t0 :type :ent)
                     (lat/add-attr :t0 :index 0)
                     (lat/add-attr :t0 :ent-type :todo)
                     (lat/add-attr :t0 :query-term {:count 1 :ent-name :_ :bind {:user :bloop}})

                     (lat/add-attr :todo-list :type :ent-type)
                     (lat/add-attr :tl-bound-t-0 :type :ent)
                     (lat/add-attr :tl-bound-t-0 :index 0)
                     (lat/add-attr :tl-bound-t-0 :ent-type :todo-list)
                     (lat/add-attr :tl-bound-t-0 :query-term {:ent-name :_ :bind {:user :bloop}})

                     (lat/add-attr :t0 :bloop :relation-attrs #{:created-by-id :updated-by-id})
                     (lat/add-attr :t0 :tl-bound-t-0 :relation-attrs #{:todo-list-id})
                     (lat/add-attr :tl-bound-t-0 :bloop :relation-attrs #{:created-by-id :updated-by-id}))]
    (is-graph= expected (:data (dc/add-ents {:schema td/schema} {:todo [[:_ {:bind {:user :bloop}}]]})))
    (is-graph= expected (:data (dc/add-ents {:schema td/schema} {:todo [{:bind {:user :bloop}}]})))))

(deftest test-add-ents-multiple-two-level-relation-binding
  (testing "only one bound todo list is created for the three todos"
    (let [expected (-> (lg/digraph [:user :bloop]
                                   [:todo-list :tl-bound-t-0]
                                   [:todo :t0]
                                   [:t0 :bloop]
                                   [:t0 :tl-bound-t-0]
                                   [:todo :t1]
                                   [:t1 :bloop]
                                   [:t1 :tl-bound-t-0]
                                   [:todo :t2]
                                   [:t2 :bloop]
                                   [:t2 :tl-bound-t-0]
                                   [:tl-bound-t-0 :bloop])

                       (lat/add-attr :user :type :ent-type)
                       (lat/add-attr :bloop :type :ent)
                       (lat/add-attr :bloop :index 0)
                       (lat/add-attr :bloop :ent-type :user)
                       (lat/add-attr :bloop :query-term {:ent-name :_ :bind {:user :bloop}})

                       (lat/add-attr :todo :type :ent-type)
                       (lat/add-attr :t0 :type :ent)
                       (lat/add-attr :t0 :index 0)
                       (lat/add-attr :t0 :ent-type :todo)
                       (lat/add-attr :t0 :query-term {:count 3 :ent-name :_ :bind {:user :bloop}})

                       (lat/add-attr :todo :type :ent-type)
                       (lat/add-attr :t1 :type :ent)
                       (lat/add-attr :t1 :index 1)
                       (lat/add-attr :t1 :ent-type :todo)
                       (lat/add-attr :t1 :query-term {:count 3 :ent-name :_ :bind {:user :bloop}})

                       (lat/add-attr :todo :type :ent-type)
                       (lat/add-attr :t2 :type :ent)
                       (lat/add-attr :t2 :index 2)
                       (lat/add-attr :t2 :ent-type :todo)
                       (lat/add-attr :t2 :query-term {:count 3 :ent-name :_ :bind {:user :bloop}})

                       (lat/add-attr :todo-list :type :ent-type)
                       (lat/add-attr :tl-bound-t-0 :type :ent)
                       (lat/add-attr :tl-bound-t-0 :index 0)
                       (lat/add-attr :tl-bound-t-0 :ent-type :todo-list)
                       (lat/add-attr :tl-bound-t-0 :query-term {:ent-name :_ :bind {:user :bloop}})

                       (lat/add-attr :t0 :bloop :relation-attrs #{:created-by-id :updated-by-id})
                       (lat/add-attr :t0 :tl-bound-t-0 :relation-attrs #{:todo-list-id})

                       (lat/add-attr :t1 :bloop :relation-attrs #{:created-by-id :updated-by-id})
                       (lat/add-attr :t1 :tl-bound-t-0 :relation-attrs #{:todo-list-id})

                       (lat/add-attr :t2 :bloop :relation-attrs #{:created-by-id :updated-by-id})
                       (lat/add-attr :t2 :tl-bound-t-0 :relation-attrs #{:todo-list-id})
                       (lat/add-attr :tl-bound-t-0 :bloop :relation-attrs #{:created-by-id :updated-by-id}))]
      (is-graph= expected (:data (dc/add-ents {:schema td/schema} {:todo [[3 {:bind {:user :bloop}}]]})))
      (is-graph= expected (:data (dc/add-ents {:schema td/schema} {:todo [{:count 3 :bind {:user :bloop}}]}))))))

(deftest test-add-ents-bound-and-uniq
  (testing "create uniq bound todo lists per todo-list-watch uniq constraint"
    (let [expected (-> (lg/digraph [:user :bloop]
                                   [:todo-list :tl-bound-tlw-0]
                                   [:tl-bound-tlw-0 :bloop]
                                   [:todo-list :tl-bound-tlw-1]
                                   [:tl-bound-tlw-1 :bloop]
                                   [:todo-list-watch :tlw0]
                                   [:tlw0 :bloop]
                                   [:tlw0 :tl-bound-tlw-0]
                                   [:todo-list-watch :tlw1]
                                   [:tlw1 :bloop]
                                   [:tlw1 :tl-bound-tlw-1])

                       (lat/add-attr :user :type :ent-type)
                       (lat/add-attr :bloop :type :ent)
                       (lat/add-attr :bloop :index 0)
                       (lat/add-attr :bloop :ent-type :user)
                       (lat/add-attr :bloop :query-term {:ent-name :_ :bind {:user :bloop}})

                       (lat/add-attr :todo-list-watch :type :ent-type)
                       (lat/add-attr :tlw0 :type :ent)
                       (lat/add-attr :tlw0 :index 0)
                       (lat/add-attr :tlw0 :ent-type :todo-list-watch)
                       (lat/add-attr :tlw0 :query-term {:count 2 :ent-name :_ :bind {:user :bloop}})

                       (lat/add-attr :todo-list-watch :type :ent-type)
                       (lat/add-attr :tlw1 :type :ent)
                       (lat/add-attr :tlw1 :index 1)
                       (lat/add-attr :tlw1 :ent-type :todo-list-watch)
                       (lat/add-attr :tlw1 :query-term {:count 2 :ent-name :_ :bind {:user :bloop}})

                       (lat/add-attr :todo-list :type :ent-type)
                       (lat/add-attr :tl-bound-tlw-0 :type :ent)
                       (lat/add-attr :tl-bound-tlw-0 :index 0)
                       (lat/add-attr :tl-bound-tlw-0 :ent-type :todo-list)
                       (lat/add-attr :tl-bound-tlw-0 :query-term {:ent-name :_ :bind {:user :bloop}})

                       (lat/add-attr :todo-list :type :ent-type)
                       (lat/add-attr :tl-bound-tlw-1 :type :ent)
                       (lat/add-attr :tl-bound-tlw-1 :index 1)
                       (lat/add-attr :tl-bound-tlw-1 :ent-type :todo-list)
                       (lat/add-attr :tl-bound-tlw-1 :query-term {:ent-name :_ :bind {:user :bloop}})

                       (lat/add-attr :tlw0 :bloop :relation-attrs #{:watcher-id})
                       (lat/add-attr :tlw0 :tl-bound-tlw-0 :relation-attrs #{:todo-list-id})

                       (lat/add-attr :tlw1 :bloop :relation-attrs #{:watcher-id})
                       (lat/add-attr :tlw1 :tl-bound-tlw-1 :relation-attrs #{:todo-list-id})

                       (lat/add-attr :tl-bound-tlw-0 :bloop :relation-attrs #{:created-by-id :updated-by-id})
                       (lat/add-attr :tl-bound-tlw-1 :bloop :relation-attrs #{:created-by-id :updated-by-id}))]
      (is-graph= expected (:data (dc/add-ents {:schema td/schema} {:todo-list-watch [[2 {:bind {:user :bloop}}]]})))
      (is-graph= expected (:data (dc/add-ents {:schema td/schema} {:todo-list-watch [{:count 2 :bind {:user :bloop}}]}))))))

(deftest test-add-ents-three-level-relation-binding
  (let [expected  (-> (lg/digraph [:user :bloop]
                                  [:attachment :a0]
                                  [:todo :t-bound-a-0]
                                  [:todo-list :tl-bound-a-0]
                                  [:a0 :bloop]
                                  [:a0 :t-bound-a-0]
                                  [:t-bound-a-0 :bloop]
                                  [:t-bound-a-0 :tl-bound-a-0]
                                  [:tl-bound-a-0 :bloop])

                      (lat/add-attr :user :type :ent-type)
                      (lat/add-attr :bloop :type :ent)
                      (lat/add-attr :bloop :index 0)
                      (lat/add-attr :bloop :ent-type :user)
                      (lat/add-attr :bloop :query-term {:ent-name :_ :bind {:user :bloop}})

                      (lat/add-attr :todo :type :ent-type)
                      (lat/add-attr :t-bound-a-0 :type :ent)
                      (lat/add-attr :t-bound-a-0 :index 0)
                      (lat/add-attr :t-bound-a-0 :ent-type :todo)
                      (lat/add-attr :t-bound-a-0 :query-term {:ent-name :_ :bind {:user :bloop}})

                      (lat/add-attr :todo-list :type :ent-type)
                      (lat/add-attr :tl-bound-a-0 :type :ent)
                      (lat/add-attr :tl-bound-a-0 :index 0)
                      (lat/add-attr :tl-bound-a-0 :ent-type :todo-list)
                      (lat/add-attr :tl-bound-a-0 :query-term {:ent-name :_ :bind {:user :bloop}})

                      (lat/add-attr :attachment :type :ent-type)
                      (lat/add-attr :a0 :type :ent)
                      (lat/add-attr :a0 :index 0)
                      (lat/add-attr :a0 :ent-type :attachment)
                      (lat/add-attr :a0 :query-term {:count 1 :ent-name :_ :bind {:user :bloop}})

                      (lat/add-attr :a0 :bloop :relation-attrs #{:created-by-id :updated-by-id})
                      (lat/add-attr :a0 :t-bound-a-0 :relation-attrs #{:todo-id})

                      (lat/add-attr :t-bound-a-0 :bloop :relation-attrs #{:created-by-id :updated-by-id})
                      (lat/add-attr :t-bound-a-0 :tl-bound-a-0 :relation-attrs #{:todo-list-id})
                      (lat/add-attr :tl-bound-a-0 :bloop :relation-attrs #{:created-by-id :updated-by-id}))]
    (is-graph= expected (:data (dc/add-ents {:schema td/schema} {:attachment [[:_ {:bind {:user :bloop}}]]})))
    (is-graph= expected (:data (dc/add-ents {:schema td/schema} {:attachment [{:bind {:user :bloop}}]})))))

(deftest test-add-ents-uniq-constraint
  (let [expected (-> (lg/digraph [:user :u0]
                                 [:todo-list :tl0]
                                 [:tl0 :u0]
                                 [:todo-list :tl1]
                                 [:tl1 :u0]
                                 [:todo-list-watch :tlw0]
                                 [:tlw0 :tl0]
                                 [:tlw0 :u0]
                                 [:todo-list-watch :tlw1]
                                 [:tlw1 :tl1]
                                 [:tlw1 :u0])

                     (lat/add-attr :user :type :ent-type)
                     (lat/add-attr :u0 :type :ent)
                     (lat/add-attr :u0 :index 0)
                     (lat/add-attr :u0 :ent-type :user)
                     (lat/add-attr :u0 :query-term {:ent-name :_})

                     (lat/add-attr :todo-list :type :ent-type)
                     (lat/add-attr :tl0 :type :ent)
                     (lat/add-attr :tl0 :index 0)
                     (lat/add-attr :tl0 :ent-type :todo-list)
                     (lat/add-attr :tl0 :query-term {:ent-name :_})

                     (lat/add-attr :todo-list :type :ent-type)
                     (lat/add-attr :tl1 :type :ent)
                     (lat/add-attr :tl1 :index 1)
                     (lat/add-attr :tl1 :ent-type :todo-list)
                     (lat/add-attr :tl1 :query-term {:ent-name :_})

                     (lat/add-attr :todo-list-watch :type :ent-type)
                     (lat/add-attr :tlw0 :type :ent)
                     (lat/add-attr :tlw0 :index 0)
                     (lat/add-attr :tlw0 :ent-type :todo-list-watch)
                     (lat/add-attr :tlw0 :query-term {:ent-name :_ :count 2})

                     (lat/add-attr :todo-list-watch :type :ent-type)
                     (lat/add-attr :tlw1 :type :ent)
                     (lat/add-attr :tlw1 :index 1)
                     (lat/add-attr :tlw1 :ent-type :todo-list-watch)
                     (lat/add-attr :tlw1 :query-term {:ent-name :_ :count 2})

                     (lat/add-attr :tl0 :u0 :relation-attrs #{:updated-by-id :created-by-id})
                     (lat/add-attr :tl1 :u0 :relation-attrs #{:updated-by-id :created-by-id})

                     (lat/add-attr :tlw0 :tl0 :relation-attrs #{:todo-list-id})
                     (lat/add-attr :tlw0 :u0 :relation-attrs #{:watcher-id})
                     (lat/add-attr :tlw1 :tl1 :relation-attrs #{:todo-list-id})
                     (lat/add-attr :tlw1 :u0 :relation-attrs #{:watcher-id}))]
    (is-graph= expected (:data (dc/add-ents {:schema td/schema} {:todo-list-watch [[2]]})))
    (is-graph= expected (:data (dc/add-ents {:schema td/schema} {:todo-list-watch [{:count 2}]})))))

(deftest test-bound-descendants?
  (is (dc/bound-descendants? (dc/init-db {:schema td/schema} {}) {:user :bibbity} :attachment))
  (is (not (dc/bound-descendants? (dc/init-db {:schema td/schema} {}) {:user :bibbity} :user)))
  (is (not (dc/bound-descendants? (dc/init-db {:schema td/schema} {}) {:attachment :bibbity} :user))))

(deftest test-add-ents-throws-exception-on-invalid-db
  (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo
                           :cljs js/Object)
                        #"db is invalid"
                        (dc/add-ents {:schema []} {})))
  (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo
                           :cljs js/Object)
                        #"query is invalid"
                        (dc/add-ents {:schema td/schema} {:user [[]]}))))

(deftest queries-can-have-anon-names
  (is (= (-> (lg/digraph [:user :u0] [:user :u1] )
             (lat/add-attr :user :type :ent-type)
             (lat/add-attr :u0 :type :ent)
             (lat/add-attr :u0 :index 0)
             (lat/add-attr :u0 :query-term {:count 1 :ent-name :_})
             (lat/add-attr :u0 :ent-type :user)
             (lat/add-attr :u1 :type :ent)
             (lat/add-attr :u1 :index 1)
             (lat/add-attr :u1 :query-term {:count 1 :ent-name :_})
             (lat/add-attr :u1 :ent-type :user))
         (:data (dc/add-ents {:schema td/schema} {:user [[:_] [:_]]})))))

(deftest test-add-ents-handles-A->A-cycles
  (testing "Handle cycles where two entities of the same type reference each other"
    (let [expected (-> (lg/digraph [:user :u0] [:user :u1] [:u0 :u1] [:u1 :u0])
                       (lat/add-attr :user :type :ent-type)
                       (lat/add-attr :u0 :type :ent)
                       (lat/add-attr :u0 :index 0)
                       (lat/add-attr :u0 :query-term {:ent-name :u0 :count 1 :refs {:updated-by-id :u1}})
                       (lat/add-attr :u0 :ent-type :user)
                       (lat/add-attr :u0 :u1 :relation-attrs #{:updated-by-id})

                       (lat/add-attr :u1 :type :ent)
                       (lat/add-attr :u1 :index 1)
                       (lat/add-attr :u1 :query-term {:ent-name :u1 :count 1 :refs {:updated-by-id :u0}})
                       (lat/add-attr :u1 :ent-type :user)
                       (lat/add-attr :u1 :u0 :relation-attrs #{:updated-by-id}))]
      (is-graph= expected (:data (dc/add-ents {:schema td/cycle-schema}
                                              {:user [[:u0 {:refs {:updated-by-id :u1}}]
                                                      [:u1 {:refs {:updated-by-id :u0}}]]})))
      (is-graph= expected (:data (dc/add-ents {:schema td/cycle-schema}
                                              {:user [{:ent-name :u0
                                                       :refs {:updated-by-id :u1}}
                                                      {:ent-name :u1
                                                       :refs {:updated-by-id :u0}}]}))))))

(deftest test-add-ents-handles-A->B-cycles
  (testing "Handle cycles where two entities of the different types reference each other"
    (let [expected (-> (lg/digraph [:todo :t0] [:todo-list :tl0] [:tl0 :t0] [:t0 :tl0])
                       (lat/add-attr :todo :type :ent-type)
                       (lat/add-attr :t0 :type :ent)
                       (lat/add-attr :t0 :index 0)
                       (lat/add-attr :t0 :query-term {:ent-name :t0 :count 1 :refs {:todo-list-id :tl0}})
                       (lat/add-attr :t0 :ent-type :todo)
                       (lat/add-attr :t0 :tl0 :relation-attrs #{:todo-list-id})

                       (lat/add-attr :todo-list :type :ent-type)
                       (lat/add-attr :tl0 :type :ent)
                       (lat/add-attr :tl0 :index 0)
                       (lat/add-attr :tl0 :query-term {:ent-name :tl0 :count 1 :refs {:first-todo-id :t0}})
                       (lat/add-attr :tl0 :ent-type :todo-list)
                       (lat/add-attr :tl0 :t0 :relation-attrs #{:first-todo-id}))]
      (is-graph= expected (-> {:schema td/cycle-schema}
                              (dc/add-ents {:todo      [[:t0 {:refs {:todo-list-id :tl0}}]]
                                            :todo-list [[:tl0 {:refs {:first-todo-id :t0}}]]})
                              :data))
      (is-graph= expected (-> {:schema td/cycle-schema}
                              (dc/add-ents {:todo      [{:ent-name :t0
                                                         :refs     {:todo-list-id :tl0}}]
                                            :todo-list [{:ent-name :tl0
                                                         :refs     {:first-todo-id :t0}}]})
                              :data)))))

(deftest test-add-ents-handles-A->self-cycles
  (testing "Handle cycles where an entity references itself"
    (is-graph= (:data (dc/add-ents {:schema td/cycle-schema} {:user [[:u0 {:refs {:updated-by-id :u0}}]]}))
               (-> (lg/digraph [:user :u0] [:u0 :u0])
                   (lat/add-attr :user :type :ent-type)
                   (lat/add-attr :u0 :type :ent)
                   (lat/add-attr :u0 :index 0)
                   (lat/add-attr :u0 :query-term {:ent-name :u0, :count 1, :refs {:updated-by-id :u0}})
                   (lat/add-attr :u0 :ent-type :user)
                   (lat/add-attr :u0 :u0 :relation-attrs #{:updated-by-id})))))

;; -----------------
;; polymorphism tests
;; -----------------

(deftest polymorphic-refs
  (let [expected (-> (lg/digraph [:topic-category :tc0] [:watch :w0] [:w0 :tc0])
                     (lat/add-attr :topic-category :type :ent-type)
                     (lat/add-attr :tc0 :type :ent)
                     (lat/add-attr :tc0 :index 0)
                     (lat/add-attr :tc0 :query-term {:ent-name :_})
                     (lat/add-attr :tc0 :ent-type :topic-category)

                     (lat/add-attr :watch :type :ent-type)
                     (lat/add-attr :w0 :type :ent)
                     (lat/add-attr :w0 :index 0)
                     (lat/add-attr :w0 :query-term {:ent-name  :_
                                                    :count     1
                                                    :refs      {:watched-id :tc0}
                                                    :ref-types {:watched-id :topic-category}})
                     (lat/add-attr :w0 :ent-type :watch)
                     (lat/add-attr :w0 :tc0 :relation-attrs #{:watched-id}))]
    (is-graph= expected (:data (dc/add-ents {:schema td/polymorphic-schema}
                                            {:watch [[1 {:refs      {:watched-id :tc0}
                                                         :ref-types {:watched-id :topic-category}}]]})))
    (is-graph= expected (:data (dc/add-ents {:schema td/polymorphic-schema}
                                            {:watch [{:refs      {:watched-id :tc0}
                                                      :ref-types {:watched-id :topic-category}}]})))))

(deftest polymorphic-refs-with-ref-name-unspecified
  ;; differs from above in that we leave out {:refs {:watched-id :tc0}}
  (let [expected (-> (lg/digraph [:topic-category :tc0] [:watch :w0] [:w0 :tc0])
                     (lat/add-attr :topic-category :type :ent-type)
                     (lat/add-attr :tc0 :type :ent)
                     (lat/add-attr :tc0 :index 0)
                     (lat/add-attr :tc0 :query-term {:ent-name :_})
                     (lat/add-attr :tc0 :ent-type :topic-category)

                     (lat/add-attr :watch :type :ent-type)
                     (lat/add-attr :w0 :type :ent)
                     (lat/add-attr :w0 :index 0)
                     (lat/add-attr :w0 :query-term {:ent-name :_ :count 1 :ref-types {:watched-id :topic-category}})
                     (lat/add-attr :w0 :ent-type :watch)
                     (lat/add-attr :w0 :tc0 :relation-attrs #{:watched-id}))]
    (is-graph= expected (:data (dc/add-ents {:schema td/polymorphic-schema}
                                            {:watch [[1 {:ref-types {:watched-id :topic-category}}]]})))
    (is-graph= expected (:data (dc/add-ents {:schema td/polymorphic-schema}
                                            {:watch [{:ref-types {:watched-id :topic-category}}]})))))

(deftest polymorphic-refs-nested
  ;; refer to topic instead of topic-category
  ;; topic depends on topic-category and will create one
  (let [expected (-> (lg/digraph [:topic-category :tc0]
                                 [:topic :t0]
                                 [:watch :w0]
                                 [:w0 :t0]
                                 [:t0 :tc0])
                     (lat/add-attr :topic-category :type :ent-type)
                     (lat/add-attr :tc0 :type :ent)
                     (lat/add-attr :tc0 :index 0)
                     (lat/add-attr :tc0 :query-term {:ent-name :_})
                     (lat/add-attr :tc0 :ent-type :topic-category)

                     (lat/add-attr :topic :type :ent-type)
                     (lat/add-attr :t0 :type :ent)
                     (lat/add-attr :t0 :index 0)
                     (lat/add-attr :t0 :query-term {:ent-name :_})
                     (lat/add-attr :t0 :ent-type :topic)
                     (lat/add-attr :t0 :tc0 :relation-attrs #{:topic-category-id})

                     (lat/add-attr :watch :type :ent-type)
                     (lat/add-attr :w0 :type :ent)
                     (lat/add-attr :w0 :index 0)
                     (lat/add-attr :w0 :query-term {:ent-name  :_
                                                    :count     1
                                                    :refs      {:watched-id :t0}
                                                    :ref-types {:watched-id :topic}})
                     (lat/add-attr :w0 :ent-type :watch)
                     (lat/add-attr :w0 :t0 :relation-attrs #{:watched-id}))]
    (is-graph= expected (:data (dc/add-ents {:schema td/polymorphic-schema}
                                            {:watch [[1 {:refs      {:watched-id :t0}
                                                         :ref-types {:watched-id :topic}}]]})))
    (is-graph= expected (:data (dc/add-ents {:schema td/polymorphic-schema}
                                            {:watch [{:refs      {:watched-id :t0}
                                                      :ref-types {:watched-id :topic}}]})))))

(deftest polymorphic-refs-with-binding
  ;; refer to topic instead of topic-category
  ;; topic depends on topic-category and will create one
  (let [expected (-> (lg/digraph [:topic-category :tc100]
                                 [:topic :t0]
                                 [:watch :w0]
                                 [:w0 :t0]
                                 [:t0 :tc100])
                     (lat/add-attr :topic-category :type :ent-type)
                     (lat/add-attr :tc100 :type :ent)
                     (lat/add-attr :tc100 :index 0)
                     (lat/add-attr :tc100 :query-term {:ent-name :_ :bind {:topic-category :tc100}})
                     (lat/add-attr :tc100 :ent-type :topic-category)

                     (lat/add-attr :topic :type :ent-type)
                     (lat/add-attr :t0 :type :ent)
                     (lat/add-attr :t0 :index 0)
                     (lat/add-attr :t0 :query-term {:ent-name :_ :bind {:topic-category :tc100}})
                     (lat/add-attr :t0 :ent-type :topic)
                     (lat/add-attr :t0 :tc100 :relation-attrs #{:topic-category-id})

                     (lat/add-attr :watch :type :ent-type)
                     (lat/add-attr :w0 :type :ent)
                     (lat/add-attr :w0 :index 0)
                     (lat/add-attr :w0 :query-term {:ent-name  :_
                                                    :count     1
                                                    :refs      {:watched-id :t0}
                                                    :bind      {:topic-category :tc100}
                                                    :ref-types {:watched-id :topic}})
                     (lat/add-attr :w0 :ent-type :watch)
                     (lat/add-attr :w0 :t0 :relation-attrs #{:watched-id}))]
    (is-graph= expected (:data (dc/add-ents {:schema td/polymorphic-schema}
                                            {:watch [[1 {:refs      {:watched-id :t0}
                                                         :bind      {:topic-category :tc100}
                                                         :ref-types {:watched-id :topic}}]]})))
    (is-graph= expected (:data (dc/add-ents {:schema td/polymorphic-schema}
                                            {:watch [{:refs      {:watched-id :t0}
                                                      :bind      {:topic-category :tc100}
                                                      :ref-types {:watched-id :topic}}]})))))

;; -----------------
;; visiting tests
;; -----------------

(deftest test-related-ents-by-attr
  (let [db (dc/add-ents {:schema td/schema} {:todo [[1]]
                                             :project [[1 {:refs {:todo-list-ids [:tl0 :tl1]}}]]})]
    (is (= :tl0
           (dc/related-ents-by-attr db :t0 :todo-list-id)))
    (is (= :u0
           (dc/related-ents-by-attr db :t0 :created-by-id)))
    (is (= [:tl0 :tl1]
           (dc/related-ents-by-attr db :p0 :todo-list-ids)))))

(deftest test-attr-map
  (let [db (dc/add-ents {:schema td/schema} {:todo [[1]]})]
    (is (= {:tl0 :todo-list
            :t0  :todo
            :u0  :user}
           (dc/attr-map db :ent-type)))
    (is (= {:u0  :user}
           (dc/attr-map db :ent-type [:u0])))))


(deftest visit-ents-once-updates-node-attrs
  (let [db (-> (dc/add-ents {:schema td/schema} {:user [[:_]]})
               (dc/visit-ents-once :custom-attr-key (constantly "yaaaaay a key")))]
    (is (= "yaaaaay a key"
           (lat/attr (:data db) :u0 :custom-attr-key)))))

(deftest visit-ents-once-does-not-override-node-attr
  (testing "If node already has attr, subsequent invocations of visit-ents-once will not overwrite it"
    (let [db (-> (dc/add-ents {:schema td/schema} {:user [[:_]]})
                 (dc/visit-ents-once :custom-attr-key (constantly "yaaaaay a key"))
                 (dc/visit-ents-once :custom-attr-key (constantly "overwrite!")))]
      (is (= "yaaaaay a key"
             (lat/attr (:data db) :u0 :custom-attr-key))))))

(deftest test-relation-attrs
  (is (= #{:todo-list-id}
         (-> (dc/add-ents {:schema td/schema} {:todo [[1]]})
             (dc/relation-attrs :t0 :tl0)))))

;; -----------------
;; visiting w/ referenced vals
;; -----------------

(def test-visiting-key :test-visit-fn)

(deftest test-assoc-referenced-vals
  (let [gen-id (fn [db {:keys [ent-name] :as v}]
                 {:id (str ent-name "-id")})]
    (testing "without custom refs"
      (is (= {:u0  {:id ":u0-id"}
              :tl0 {:id            ":tl0-id"
                    :created-by-id ":u0-id"
                    :updated-by-id ":u0-id"}
              :t0  {:id            ":t0-id"
                    :todo-list-id  ":tl0-id"
                    :created-by-id ":u0-id"
                    :updated-by-id ":u0-id"}}
             (-> (dc/add-ents {:schema td/schema} {:todo [[1]]})
                 (dc/visit-ents-once test-visiting-key [gen-id dc/assoc-referenced-vals])
                 (dc/attr-map test-visiting-key)))))

    (testing "with custom refs"
      (is (= {:custom-user {:id ":custom-user-id"}
              :tl0         {:id            ":tl0-id"
                            :created-by-id ":custom-user-id"
                            :updated-by-id ":custom-user-id"}}
             (-> (dc/add-ents
                  {:schema td/schema}
                  {:todo-list [[1 {:refs {:created-by-id :custom-user
                                          :updated-by-id :custom-user}}]]})
                 (dc/visit-ents-once test-visiting-key [gen-id dc/assoc-referenced-vals])
                 (dc/attr-map test-visiting-key)))))

    (testing "with overwrites"
      (is (= {:custom-user {:id ":overwritten-id"}
              :tl0         {:id            ":tl0-id"
                            :created-by-id ":overwritten-id"
                            :updated-by-id ":overwritten-id"}}
             (-> (dc/add-ents
                  {:schema td/schema}
                  {:user      [[:custom-user {test-visiting-key {:set {:id ":overwritten-id"}}}]]
                   :todo-list [[1 {:refs {:created-by-id :custom-user
                                          :updated-by-id :custom-user}}]]})
                 (dc/visit-ents-once test-visiting-key [gen-id
                                                        dc/merge-overwrites
                                                        dc/assoc-referenced-vals])
                 (dc/attr-map test-visiting-key)))))))

(deftest test-visit-self-reference
  (let [user-gen (fn [query]
                   (-> (dc/add-ents {:schema td/cycle-schema} query)
                       (dc/visit-ents-once :gen
                                           [(let [id (partial swap!
                                                              (atom 0)
                                                              inc)]
                                              (fn [_ _]
                                                (let [i (id)]
                                                  {:id i :updated-by-id i})))
                                            dc/merge-overwrites
                                            dc/assoc-referenced-vals])
                       (dc/attr-map :gen)))]
    (is (= {:u0 {:id 1, :updated-by-id 1}} (user-gen {:user [[1]]}))
        "only create one entity with self reference")
    (testing "self reference can be specified explicitly"
      (let [{:keys [u0 u1]} (user-gen
                             {:user [[1]
                                     [1 {:refs {:updated-by-id :u1}}]]})]
        (is (= (:id u0) (:updated-by-id u0)))
        (is (= (:id u1) (:updated-by-id u1)))
        (is (not= (:id u0) (:id u1)))
        (is (not= (:version u0) (:updated-by-id u1)))))))

;;---
;; generating and inserting
;;---

(deftest test-wrap-generate-visit-fn
  (let [gen-id (dc/wrap-generate-visit-fn
                (fn [_db {:keys [ent-name]}]
                  {:id (str ent-name "-id")}))]
    (is (= {:custom-user {:id ":overwritten-id"}
            :tl0         {:id            ":tl0-id"
                          :created-by-id ":visiting-overwritten-id"
                          :updated-by-id ":overwritten-id"}}
           (-> (dc/add-ents
                {:schema td/schema}
                {:user      [[:custom-user {test-visiting-key {:set {:id ":overwritten-id"}}}]]
                 :todo-list [[1 {:refs {:created-by-id :custom-user
                                        :updated-by-id :custom-user}
                                 test-visiting-key {:set {:created-by-id ":visiting-overwritten-id"}}}]]})
               (dc/visit-ents-once test-visiting-key gen-id)
               (dc/attr-map test-visiting-key))))))

(deftest test-wrap-generate-visit-fn-overwrites
  (let [gen-id      (dc/wrap-generate-visit-fn
                     (fn [_db {:keys [ent-name]}]
                       {:id (str ent-name "-id")}))
        test-schema (assoc-in td/schema [:user test-visiting-key :set] {:id ":schema-overwrite"})]
    (is (= {:custom-user   {:id ":overwritten-id"}
            :custom-user-2 {:id ":schema-overwrite"}
            :tl0           {:id            ":tl0-id"
                            :created-by-id ":visiting-overwritten-id"
                            :updated-by-id ":overwritten-id"}}
           (-> (dc/add-ents
                {:schema test-schema}
                {:user      [[:custom-user {test-visiting-key {:set {:id ":overwritten-id"}}}]
                             [:custom-user-2]]
                 :todo-list [[1 {:refs {:created-by-id :custom-user
                                        :updated-by-id :custom-user}
                                 test-visiting-key {:set {:created-by-id ":visiting-overwritten-id"}}}]]})
               (dc/visit-ents-once test-visiting-key gen-id)
               (dc/attr-map test-visiting-key))))))

(deftest test-wrap-incremental-insert-visit-fn
  (let [inserted (atom [])
        gen-id   (dc/wrap-generate-visit-fn
                  (fn [_db {:keys [ent-name]}]
                    {:id (str ent-name "-id")}))
        insert   (dc/wrap-incremental-insert-visit-fn
                  :gen
                  (fn [_db {:keys [visit-val]}]
                    (swap! inserted conj visit-val)
                    visit-val))]
    (-> (dc/add-ents
         {:schema td/schema}
         {:user      [[:custom-user {:gen {:set {:id ":overwritten-id"}}}]]
          :todo-list [[1 {:refs {:created-by-id :custom-user
                                 :updated-by-id :custom-user}
                          :gen  {:set {:created-by-id ":this-wont-be-used"}}}]]})
        (dc/visit-ents-once :gen gen-id)
        (dc/visit-ents-once :insert insert))

    (is (= [{:id ":overwritten-id"}
            {:id            ":tl0-id"
             :created-by-id ":overwritten-id"
             :updated-by-id ":overwritten-id"}]
           @inserted))

    (reset! inserted [])

    (-> (dc/add-ents
         {:schema td/schema}
         {:user      [[:custom-user {:gen {:set {:id ":overwritten-id"}}}]]
          :todo-list [[1 {:refs   {:created-by-id :custom-user
                                   :updated-by-id :custom-user}
                          :insert {:set {:created-by-id ":this-will-be-used"}}}]]})
        (dc/visit-ents-once :gen gen-id)
        (dc/visit-ents-once :insert insert))

    (is (= [{:id ":overwritten-id"}
            {:id            ":tl0-id"
             :created-by-id ":this-will-be-used"
             :updated-by-id ":overwritten-id"}]
           @inserted))))

;; -----------------
;; view tests
;; -----------------

(deftest test-query-ents
  (is (= [:t0]
         (dc/query-ents (dc/add-ents {:schema td/schema} {:todo [[1]]}))))

  (is (= #{:t0 :u0}
         (set (dc/query-ents (dc/add-ents {:schema td/schema} {:user [[1]]
                                                               :todo [[1]]}))))))

(deftest test-coll-relation-attr?
  (let [db (dc/add-ents {:schema td/schema} {:project [[1]]})]
    (is (dc/coll-relation-attr? db :p0 :todo-list-ids))
    (is (not (dc/coll-relation-attr? db :p0 :created-by-id)))))

(deftest test-ents-by-type
  (let [db (dc/add-ents {:schema td/schema} {:project [[1]]})]
    (is (= {:user #{:u0}
            :todo-list #{:tl0}
            :project #{:p0}}
           (dc/ents-by-type db)))
    (is (= {:user #{:u0}}
           (dc/ents-by-type db [:u0])))))

(deftest test-ent-relations
  (let [db (dc/add-ents {:schema td/schema}
                        {:project [[:p0 {:refs {:todo-list-ids 2}}]]
                         :todo    [[1]]})]
    (is (= {:created-by-id :u0
            :updated-by-id :u0
            :todo-list-ids #{:tl0 :tl1}}
           (dc/ent-relations db :p0)))
    (is (= {:created-by-id :u0
            :updated-by-id :u0
            :todo-list-id  :tl0}
           (dc/ent-relations db :t0)))))

(deftest test-all-ent-relations
  (let [db (dc/add-ents {:schema td/schema}
                        {:project [[:p0 {:refs {:todo-list-ids 2}}]]})]
    (is (= {:project   {:p0 {:created-by-id :u0
                             :updated-by-id :u0
                             :todo-list-ids #{:tl0 :tl1}}}
            :user      {:u0 {}}
            :todo-list {:tl0 {:created-by-id :u0
                              :updated-by-id :u0}
                        :tl1 {:created-by-id :u0
                              :updated-by-id :u0}}}
           (dc/all-ent-relations db)))
    (is (= {:project   {:p0 {:created-by-id :u0
                             :updated-by-id :u0
                             :todo-list-ids #{:tl0 :tl1}}}
            :todo-list {:tl0 {:created-by-id :u0
                              :updated-by-id :u0}}}
           (dc/all-ent-relations db [:p0 :tl0])))
    (is (= {:project   {:p0 {:created-by-id :u0
                             :updated-by-id :u0
                             :todo-list-ids #{:tl0 :tl1}}}}
           (dc/all-ent-relations db [:p0])))))

;; -----------------
;; validation
;; -----------------

(deftest assert-schema-refs-must-exist
  (is (thrown-with-msg? #?(:clj java.lang.AssertionError
                           :cljs js/Error)
                        #"Your schema relations reference nonexistent types: "
                        (dc/add-ents {:schema {:user {:relations {:u1 [:circle :circle-id]}}}}
                                     {}))))

(deftest assert-no-dupe-prefixes
  (is (thrown-with-msg? #?(:clj java.lang.AssertionError
                           :cljs js/Error)
                        #"You have used the same prefix for multiple entity types: "
                        (dc/add-ents {:schema {:user  {:prefix :u}
                                               :user2 {:prefix :u}}}
                                     {}))))

(deftest assert-constraints-must-ref-existing-relations
  (is (thrown-with-msg? #?(:clj java.lang.AssertionError
                           :cljs js/Error)
                        #"Schema constraints reference nonexistent relation attrs: \{:user #\{:blarb\}\}"
                        (dc/add-ents {:schema {:user  {:prefix :u
                                                       :constraints {:blarb :coll}}}}
                                     {}))))

(deftest assert-query-does-not-contain-unknown-ent-types
  (is (thrown-with-msg? #?(:clj java.lang.AssertionError
                           :cljs js/Error)
                        #"The following ent types are in your query but aren't defined in your schema: #\{:bluser\}"
                        (dc/add-ents {:schema {:user  {:prefix :u}}}
                                     {:bluser [[1]]}))))

(deftest enforces-coll-schema-constraints
  (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo
                           :cljs js/Object)
                        #"Query-relations for coll attrs must be a number or vector"
                        (dc/add-ents {:schema td/schema} {:project [[:_ {:refs {:todo-list-ids :tl0}}]]}))))

(deftest enforces-unary-schema-constraints
  (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo
                           :cljs js/Object)
                        #"Query-relations for unary attrs must be a keyword"
                        (dc/add-ents {:schema td/schema} {:attachment [[:_ {:refs {:todo-id [:t0 :t1]}}]]}))))
