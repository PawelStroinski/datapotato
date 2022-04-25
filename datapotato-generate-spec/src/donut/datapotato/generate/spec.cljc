(ns donut.datapotato.generate.spec
  (:require
   [clojure.spec.alpha :as s]
   [clojure.spec.gen.alpha :as gen]
   [donut.datapotato.core :as dd]))

(def visit-key :generate)

(def generate-visiting-fn
  (dd/wrap-generate-visiting-fn
   (fn [db {:keys [ent-name]}]
     (-> db
         (dd/ent-schema ent-name)
         visit-key
         :spec
         s/gen
         gen/generate))))

(defn generate
  "Convenience function to build a new db and generate values for each ent"
  [db query]
  (-> (dd/add-ents db query)
      (dd/visit-ents-once visit-key generate-visiting-fn)))

(defn generate-attr-map
  "Convenience function to return a map of `{ent-name generated-data}` using
  the db returned by `generate`"
  [db query]
  (-> (generate db query)
      (dd/attr-map visit-key)))
