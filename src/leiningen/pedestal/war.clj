(ns leiningen.pedestal.war
  (:require [clojure.data.xml :as xml]
            [clojure.java.io :as io]
            [leiningen.core.main :refer (leiningen-version)]
            [leiningen.compile :as compile] ;abo
            [leiningen.pedestal.util :as u] ;abo
            [leinjacker.deps :as deps] ;abo
            )
  (:import (java.io ByteArrayInputStream
                    FileOutputStream)
           (java.util.jar Manifest
                          JarEntry
                          JarOutputStream)))

;; This is heavily influenced from the lein-ring code

;; web.xml construction
;; --------------------

(defn description [project]
  (get-in project [:pedestal :servlet-description]
          (str (:name project) "-" (:version project) " Pedestal HTTP Servlet")))

(defn display-name [project]
  (get-in project [:pedestal :servlet-display-name]
          (description project)))

(defn servlet-name [project]
  (get-in project [:pedestal :servlet-name] "PedestalServlet"))

(defn servlet-class [project]
 (get-in project [:pedestal :servlet-class] "io.pedestal.servlet.ClojureVarServlet"))

(defn app-server-ns [project]
  (or (get-in project [:pedestal :server-ns]) ;; We need to do `or` to prevent a premature exit
      (do
        (println "ERROR: You need to specify a pedestal server-ns in your project.clj"
                 (str "Your current pedestal settings: " (:pedestal project))
                 "Quitting...")
        (System/exit 1))))

(defn url-pattern [project]
  (get-in project [:pedestal :url-pattern] "/*"))

(defn make-web-xml [project]
  (if-let [web-xml (get-in project [:pedestal :web-xml])]
    (slurp web-xml)
    (let [server-ns (app-server-ns project)]
      (xml/indent-str
        (xml/sexp-as-element
          [:web-app {:xmlns              "http://java.sun.com/xml/ns/javaee"
                     :xmlns:xsi          "http://www.w3.org/2001/XMLSchema-instance"
                     :xsi:schemaLocation "http://java.sun.com/xml/ns/javaee http://java.sun.com/xml/ns/javaee/web-app_3_0.xsd"
                     :version            "3.0"
                     :metadata-complete  "true"}
           [:description (description project)]
           [:display-name (display-name project)]
           [:servlet
            [:servlet-name (servlet-name project)]
            [:servlet-class (servlet-class project)]
            [:init-param
             [:param-name "init"]
             [:param-value (str server-ns "/servlet-init")]]
            [:init-param
             [:param-name "service"]
             [:param-value (str server-ns "/servlet-service")]]
            [:init-param
             [:param-name "destroy"]
             [:param-value (str server-ns "/servlet-destroy")]]]
           [:servlet-mapping
            [:servlet-name (servlet-name project)]
            [:url-pattern (url-pattern project)]]])))))

;; Manifest construction
;; ---------------------

(def default-pedestal-manifest
    {"Created-By" (str "Leiningen Pedestal Plugin with Leiningen " (leiningen-version))
     "Built-By"  (System/getProperty "user.name")
     "Build-Jdk"  (System/getProperty "java.version")})

(defn manifest-str [project]
  (reduce
     (fn [accumulated-manifest [k v]]
       (str accumulated-manifest "\n" k ": " v))
     "Manifest-Version: 1.0"
     (merge default-pedestal-manifest (get-in project [:pedestal :manifest]))))

;; This is taken from Ring, it probably should be clojure.java.io
(defn string-input-stream [^String s]
  "Returns a ByteArrayInputStream for the given String."
  (ByteArrayInputStream. (.getBytes s)))

(defn make-manifest [project]
  (Manifest. (string-input-stream (manifest-str project))))

;; War construction
;; -----------------

;abo>>
(defn default-war-name [project]
  (or (get-in project [:pedestal :war-name])
      (str (:name project) "-" (:version project) ".war")))
;abo<<

(defn war-name
  ([project]
   (war-name project ""))
  ([project extra]
   (get-in project [:pedestal :war-name]
          (str (:name project) "-" (:version project) extra ".war"))))

(defn war-file-path [project war-name]
  (let [target-dir (get project :target-dir
                        (:target-path project))]
    (.mkdirs (io/file target-dir))
    (str target-dir "/" war-name)))

(defn skip-file? [project war-path file]
  (or (re-find #"^\.?#" (.getName file))
      (re-find #"~$" (.getName file))
      (some #(re-find % war-path)
            (get-in project [:pedestal :war-exclusions] [#"(^|/)\."]))))

(defn war-resources-paths [project]
  (keep identity
    (distinct
      (concat [(get-in project [:pedestal :war-resources-path] "war-resources")]
              (get-in project [:pedestal :war-resource-paths])))))

(defn in-war-path [war-path root file]
  (str war-path
       (-> (.toURI (io/file root))
           (.relativize (.toURI file))
           (.getPath))))

(defn write-entry [war war-path entry]
    (.putNextEntry war (JarEntry. war-path))
    (io/copy entry war))

(defn file-entry [war project war-path file]
  (when (and (.exists file)
             (.isFile file)
             (not (skip-file? project war-path file)))
    (write-entry war war-path file)))

(defn dir-entry [war project war-root dir-path]
  (doseq [file (file-seq (io/file dir-path))]
    (let [war-path (in-war-path war-root dir-path file)]
      (file-entry war project war-path file))))

;; A given `postprocess-fn` must take two args, project and the war/war-stream

(defn write-war [project war-path & postprocess-fns]
  (with-open [war-stream (-> (io/output-stream war-path)
                             (JarOutputStream. (make-manifest project)))]
    (doto war-stream
      (write-entry "WEB-INF/web.xml" (string-input-stream (make-web-xml project)))
      ;abo>>
      ;(and (get-in project [:pedestal :include-compile-path])
      ;     (dir-entry project "WEB-INF/classes/" (:compile-path project)))
      (dir-entry project "WEB-INF/classes/" (:compile-path project))
      ;abo<<
      )
    ;abo>>
    ;(doseq [path (distinct (concat [(:source-path project)] (:source-paths project)
    ;                               [(:resources-path project)] (:resource-paths project)))
    ;        :when path]
    (doseq [path (u/source-and-resource-paths project)
            :when path]
      ;abo<<
      (dir-entry war-stream project "WEB-INF/classes/" path))
    (doseq [path (war-resources-paths project)]
      (dir-entry war-stream project "" path))
    (doseq [pp-fn postprocess-fns]
      (pp-fn project war-stream))
    war-stream))

;abo>>
(defn add-servlet-dep [project]
  (-> project
      ;(deps/add-if-missing ['ring/ring-servlet ring-version])
      (deps/add-if-missing '[javax.servlet/javax.servlet-api "3.1.0"])))
;abo<<

(defn war
  "Create a $PROJECT-$VERSION.war file."
  ;abo>>
  ;([project]
  ; (war project (war-name project)))
  ;([project war-name-str]
  ; (let [war-path (war-file-path project war-name-str)
  ;       app-ns (app-server-ns project)
  ;       result (compile/compile project)]
  ;   (write-war project war-path)
  ;   (println "Created" war-path)
  ;   war-path)))
  ([project]
   (war project (default-war-name project)))
  ([project war-name & {:keys [profiles-to-merge additional-writes]}]
   (let [project (-> project
                     (u/unmerge-profiles [:default])
                     (u/merge-profiles profiles-to-merge)
                     add-servlet-dep
                     )
         result  (compile/compile project)]
     (when-not (and (number? result) (pos? result))
       (let [war-path (war-file-path project war-name)]
         ;(compile-servlet project)
         ;(compile-listener project)
         ;(prepare-war project war-path additional-writes)
         (write-war project war-path additional-writes)
         (println "Created" war-path)
         war-path)))))
  ;abo<<
