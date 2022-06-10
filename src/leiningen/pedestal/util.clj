;abo>>
(ns leiningen.pedestal.util
  (:require [clojure.pprint :refer [pprint]]
            [leiningen.core.project :as project]))

(defn unmerge-profiles [project profiles]
  ;(if-let [unmerge-fn (and (= 2 (lju/lein-generation))
  ;                         (lju/try-resolve 'leiningen.core.project/unmerge-profiles))]
  ;  (unmerge-fn project profiles)
  ;  project))
  (let [new-project (project/unmerge-profiles project profiles)]
    ;(println "unmerge-profiles")
    ;(println "old-project")
    ;(pprint project)
    ;(println "new-project")
    ;(pprint new-project)
    new-project))

(defn merge-profiles [project profiles]
  ;(if-let [merge-fn (and (= 2 (lju/lein-generation))
  ;                       (lju/try-resolve 'leiningen.core.project/merge-profiles))]
  ;  (merge-fn project profiles)
  ;  project))
  (let [new-project (project/merge-profiles project profiles)]
    ;(println "merge-profiles")
    ;(println "old-project")
    ;(pprint project)
    ;(println "new-project")
    ;(pprint new-project)
    new-project))

(defn source-and-resource-paths
  "Return a distinct sequence of the project's source and resource paths,
  unless :omit-source is true, in which case return only resource paths."
  [project]
  (let [resource-paths (concat [(:resources-path project)] (:resource-paths project))
        source-paths (if (:omit-source project)
                       '()
                       (concat [(:source-path project)] (:source-paths project)))]
    (distinct (concat source-paths resource-paths))))
;abo<<
