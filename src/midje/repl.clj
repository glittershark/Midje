(ns ^{:doc "Functions useful when using Midje in the repl or from the command line.
            See `midje-repl-help` for details."}
  midje.repl
  (:use [bultitude.core :only [namespaces-in-dir namespaces-on-classpath]]
        clojure.pprint)
  (:require midje.sweet
            [midje.ideas.facts :as fact]
            [midje.internal-ideas.compendium :as compendium]
            [midje.ideas.reporting.levels :as levelly]
            [midje.ideas.metadata :as metadata]
            [midje.doc :as doc]
            [midje.config :as config]
            [midje.util.form-utils :as form]
            [midje.util.colorize :as color]
            [midje.util.ecosystem :as ecosystem]
            [midje.util.namespace :as namespace]))

(when (and (ecosystem/running-in-repl?) (ecosystem/clojure-1-2-X?))
  (println (color/fail "The Midje repl tools don't work on Clojure 1.2.X")))
(ecosystem/when-1-3+

;; Supply function to leiningen.core.project.
;; Note that it does not work to put the following code into
;; midje.util.backwards-compatible-utils.

(in-ns 'clojure.core)
(if-not (resolve 'ex-info)
  (defn ex-info
    ([msg map]
       (RuntimeException. msg))
    ([msg map cause]
       (RuntimeException. msg cause))))
(in-ns 'midje.repl)
(require '[leiningen.core.project :as project])

(namespace/immigrate-from 'midje.ideas.metadata
                          (map metadata/metadata-function-name
                               metadata/fact-properties))

(when (doc/appropriate?)
  (namespace/immigrate-from 'midje.doc doc/for-repl)
  (doc/repl-notice))

(when-not (ns-resolve 'user '=>) ; when not already `use`d.
  (namespace/immigrate 'midje.sweet))



                                ;;; User Intentions

;; We go to some trouble to have a mostly-consistent interface to the functions
;; {load,fetch,check,forget}-facts. The arguments to the functions are bundled
;; together into a map that controls:
;;
;; Which namespaces should be used.
;; Which output should be printed
;; Whether facts in namespaces should be filtered.
;; Whether the default case for future uses should be changed.


;; The `:all` keyword means "do this function to all namespaces". 
(defn- do-to-all? [args]
  (boolean (some #{:all} args)))
;; It has to be distinguished from arguments that set up filters.
(def ^{:private true} all-keyword-is-not-a-filter
  (partial = :all))


;; Loading has a different way of naming namespaces than the other
;; functions. (For example, it supports wildcards.) So there are two
;; sets of defaults: one for it, one for the other functions. (I use
;; fetch as an exemplar.)

(def ^{:private true, :testable true}
  default-args (atom {:memory-command
                      {:given-namespace-args [:all]
                       :given-filter-args []
                       :given-level-args []}
                      :disk-command
                      {:given-namespace-args [:all]
                       :given-filter-args []
                       :given-level-args []}}))

;; Depending on the function chosen, the user may intend to update
;; neither, both, or only the fetch-class default.

(defn- update-one-default! [intention command-type]
  (swap! default-args
         assoc command-type (select-keys intention
                                  [command-type :given-filter-args :given-level-args])))

(defn- ^{:testable true} and-update-defaults! [intention command-type]
  (update-one-default! intention :memory-command)
  (when (= command-type :disk-command)
    (update-one-default! intention :disk-command)))
(defn- without-updating-defaults [intention scope] "do nothing")



;; When referring to namespaces on disk, the user intends
;; a swath of namespaces. These functions find them.
(defn- ^{:testable true} project-directories []
  (try
    (let [project (project/read)]
      (concat (:test-paths project) (:source-paths project)))
    (catch java.io.FileNotFoundException e
      ["test"])))

(defn- ^{:testable true} project-namespaces []
  (mapcat namespaces-in-dir (project-directories)))

(defn- ^{:testable true} unglob-partial-namespaces [namespaces]
  (mapcat #(if (= \* (last %))
             (namespaces-on-classpath :prefix (apply str (butlast %)))
             [(symbol %)])
          (map str namespaces)))


;; This function makes user intentions explicit.

(defn- ^{:testable true} defaulting-args [original-args command-type]
  (let [[given-level-seq print-level-to-use args]
          (levelly/separate-print-levels original-args)
        [filters filter-function args]
        (metadata/separate-filters args all-keyword-is-not-a-filter)]

    (if (empty? args)
      (let [command-components (command-type @default-args)]
        (defaulting-args (apply concat (vals command-components))
                         command-type))
        
      {:given-namespace-args args
       :given-filter-args filters
       :given-level-args given-level-seq
       
       :all? (do-to-all? args)
       :print-level print-level-to-use
       :filter-function filter-function})))


(defmulti deduce-user-intention
  "This has to be public because multimethods are second-class."
  (fn [_ namespace-source] namespace-source))
  
(defmethod deduce-user-intention :memory-command [original-args command-type]
  (let [base (defaulting-args original-args command-type)]
    (merge base
           {:namespaces-to-use (:given-namespace-args base)
            command-type (:given-namespace-args base)})))

(defmethod deduce-user-intention :disk-command [original-args command-type]
  (let [base (defaulting-args original-args :disk-command)]
    (merge base
           {command-type (:given-namespace-args base)}
           (if (:all? base)
             {:namespaces-to-use (project-namespaces)
              :memory-command [:all]}
             (let [expanded (unglob-partial-namespaces (:given-namespace-args base))]
               {:namespaces-to-use expanded
                :memory-command expanded})))))

;;; A DSLish way of defining intention-obeying functions.

(defmacro ^{:private true} def-obedient-function
  [command-type function-name update worker-function docstring]
  `(defn ~function-name
     ~docstring
     [& args#]
     (let [intention# (deduce-user-intention args# ~command-type)
           result# (~worker-function intention#)]
       ;; By doing this after calculating the result, we prevent
       ;; a bad set of arguments from polluting the defaults.
       (~update intention# ~command-type)
       result#)))



                                ;;; Loading facts from the repl

(def-obedient-function :disk-command load-facts and-update-defaults!
  (fn [intention]
    (config/with-augmented-config {:print-level (:print-level intention)
                                   :desired-fact? (:filter-function intention)}
      (levelly/forget-past-results)
      (doseq [ns (:namespaces-to-use intention)]
        (compendium/remove-namespace-facts-from! ns)
        ;; Following strictly unnecessary, but slightly useful because
        ;; it reports the changed namespace before the first fact loads.
        ;; That way, some error in the fresh namespace won't appear to
        ;; come from the last-loaded namespace.
        (levelly/report-changed-namespace ns)
        (require ns :reload))
      (levelly/report-summary)
      nil))
  "Load given namespaces, as in:
     (load-facts 'midje.t-sweet 'midje.t-repl)

   A partial namespace ending in a `*` will load all sub-namespaces.
   Example: (load-facts 'midje.ideas.*)

   If the :all argument is given, all the namespaces in the project.clj's
   :test-paths and :source-paths will be loaded.
   But if there's no project.clj, all namespaces under \"test\"
   will be loaded.

   By default, all facts are loaded from the namespaces. You can, however,
   add further arguments. Only facts matching one or more of the arguments
   are loaded. The arguments are:

   :keyword      -- Does the metadata have a truthy value for the keyword?
   \"string\"    -- Does the fact's name contain the given string? 
   #\"regex\"    -- Does any part of the fact's name match the regex?
   a function    -- Does the function return a truthy value when given
                    the fact's metadata?

   In addition, you can adjust what's printed during loading.
   See `(doc midje-print-levels)`.

   If `load-facts` is given no arguments, it reuses the previous arguments."
)

                                ;;; Fetching loaded facts

;; An independent function because it's not just used by fetch-facts.
(defn- fetch-intended-facts [intention]
  (let [fact-functions (if (:all? intention)
                         (compendium/all-facts<>)
                         (mapcat compendium/namespace-facts<> (:namespaces-to-use intention)))]
    (filter (:filter-function intention) fact-functions)))

(def-obedient-function :memory-command fetch-facts and-update-defaults!
  fetch-intended-facts
  "Fetch facts that have already been defined, whether by loading
   them from a file or via the repl.

   (fetch-facts *ns* 'midje.t-repl)  -- facts defined in these namespaces
   (fetch-facts :all)                -- all known facts

   You can further filter the facts by giving more arguments. Facts matching
   any of the arguments are included in the result. The arguments are:

   :keyword      -- Does the metadata have a truthy value for the keyword?
   \"string\"    -- Does the fact's name contain the given string? 
   #\"regex\"    -- Does any part of the fact's name match the regex?
   a function    -- Does the function return a truthy value when given
                    the fact's metadata?

   If no arguments are given, it reuses the arguments from the most
   recent `check-facts`, `fetch-facts`, or `load-facts`."
)
     

                              ;;; Forgetting loaded facts

(def-obedient-function :memory-command forget-facts without-updating-defaults
  (fn [intention]
    ;; a rare concession to efficiency
    (cond (and (empty? (:given-filter-args intention)) (:all? intention))
          (compendium/fresh!)
          
          (empty? (:given-filter-args intention))
          (dorun (map compendium/remove-namespace-facts-from!
                      (:namespaces-to-use intention)))
          
          :else
          (dorun (map compendium/remove-from!
                      (fetch-intended-facts intention)))))
  "Forget defined facts so that they will not be found by `check-facts`
   or `fetch-facts`.

   (forget-facts *ns* midje.t-repl -- defined in named namespaces
   (forget-facts :all)             -- defined anywhere
   (forget-facts)                  -- forget facts worked on by most
                                      recent `check-facts` or `load-facts`.

   You can further filter the facts by giving more arguments. Facts matching
   any of the arguments are the ones that are forgotten. The arguments are:

   :keyword      -- Does the metadata have a truthy value for the keyword?
   \"string\"    -- Does the fact's name contain the given string? 
   #\"regex\"    -- Does any part of the fact's name match the regex?
   a function    -- Does the function return a truthy value when given
                    the fact's metadata?"
  )
     

    
                                ;;; Checking loaded facts

(def ^{:doc "Check a single fact. Takes as its argument a function such
    as is returned by `last-fact-checked`."}
  check-one-fact fact/check-one)

(defn- check-facts-once-given [fact-functions]
  (levelly/forget-past-results)
  (let [results (doall (map check-one-fact fact-functions))]
    (levelly/report-summary)
    (every? true? results)))

(def-obedient-function :memory-command check-facts and-update-defaults!
  (fn [intention]
    (config/with-augmented-config {:print-level (:print-level intention)}
      (check-facts-once-given (fetch-intended-facts intention))))
  "Check facts that have already been defined.

   (check-facts *ns* midje.t-repl -- defined in named namespaces
   (check-facts :all)             -- defined anywhere

   You can further filter the facts by giving more arguments. Facts matching
   any of the arguments are the ones that are checked. The arguments are:

   :keyword      -- Does the metadata have a truthy value for the keyword?
   \"string\"    -- Does the fact's name contain the given string? 
   #\"regex\"    -- Does any part of the fact's name match the regex?
   a function    -- Does the function return a truthy value when given
                    the fact's metadata?

   In addition, you can adjust what's printed. See `(doc midje-print-levels)`.

   If no arguments are given, it reuses the arguments from the most
   recent `check-facts`, `fetch-facts`, or `load-facts`."
  )
    


                                ;;; The history of checked facts

(defn last-fact-checked
  "The last fact or tabular fact that was checked. Only top-level
   facts are recorded, not facts nested within them."
  []
  (compendium/last-fact-checked<>))

(defn source-of-last-fact-checked 
  "Returns the source of the last fact or tabular fact run."
  []
  (fact-source (last-fact-checked)))

(defn recheck-fact 
  "Recheck the last fact or tabular fact that was checked.
   When facts are nested, the entire outer-level fact is rechecked.
   The result is true if the fact checks out.

   The optional argument lets you adjust what's printed.
   See `(print-level-help)` for legal values."
  ([]
     (check-facts-once-given [(last-fact-checked)]))
  ([print-level]
     (config/with-augmented-config {:print-level print-level}
       (recheck-fact))))

(def ^{:doc "Synonym for `recheck-fact`."} rcf recheck-fact)


)
