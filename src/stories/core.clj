(ns stories.core
  (:require [clojure.string :as s]
            [stories.massland :as massland]
            [stories.cambridge-prop-db :as cpdb]
            [stories.somerville-prop-db :as spdb]))

(defn scrape []
  (let [street-num 22
        street-name "Grand View"
        city "Somerville"]
    (massland/scrape street-num street-name city)

    (case (s/lower-case city)
      "cambridge" (cpdb/scrape street-num street-name city)
      "somerville" (spdb/scrape street-num street-name city)
      "default")))
