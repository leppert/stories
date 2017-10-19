(ns stories.util
  (:require [clojure.java.shell :refer [sh]]
            [sparkledriver.browser :as brw]
            [sparkledriver.retry :refer [*retry-fn*]]))

(defn retry-const
  ([frequency timeout try-fn] (retry-const frequency timeout nil try-fn))
  ([frequency timeout recover-fn try-fn]
   (loop [retry 0]
     (let [[success result ex] (try
                                 [true (try-fn) nil]
                                 (catch Exception e [false nil e]))]
       (if success
         result
         (let [x-info (ex-data ex)]
           (when (= (:cause x-info) :unhandled-fatal) ; bubble up :unhandled-fatal to escape retries
             (throw ex))
           (if (> (* retry frequency) timeout)
             (throw (Exception. (str (.getMessage ex) " - timeout exceeded, too many retries!")))
             (do
               (when recover-fn
                 (recover-fn ex))
               (Thread/sleep frequency)
               (recur (inc retry))))))))))

(defn curl-download [url dest]
  (sh "curl" "-Lo" dest url))

(defn focus-popup [browser]
  (*retry-fn* #(if (> (count (brw/all-windows browser)) 1)
                 (brw/switch-to-window browser (last (brw/all-windows browser)))
                 (throw (Exception. "Popup failed to open.")))))

(defn focus-main [browser]
  (brw/switch-to-window browser (first (brw/all-windows browser))))
