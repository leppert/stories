(ns stories.core
  (:require [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as s]
            [clj-http.client :as client]
            [sparkledriver.cookies :refer [browser-cookies->map]]
            [sparkledriver.browser :as brw]
            [sparkledriver.element :as el]
            [sparkledriver.retry :refer [with-retry retry-backoff *retry-fn*]]))

(def rows-xpath "//div[@id='DocList1_ContentContainer1']//tr//tr[.//input[@type='checkbox']]")

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

(defn download [url dest]
  (sh "curl" "-Lo" dest url))

(defn get-img-props
  [element]
  (let [size (el/size element)]
    {:src (el/attr element "src")
     :area (* (.getWidth size) (.getHeight size))}))

(defn get-largest-img
  [browser]
  (->> (el/find-by-tag* browser "img")
       (filter el/displayed?)
       (map get-img-props)
       (reduce #(if (> (:area %1) (:area %2)) %1 %2))))

(defn property-search
  [browser street-num street-name city]
  ; select Property Search
  (brw/execute-script browser "__doPostBack('Navigator1$SearchCriteria1$LinkButton03','')")

  ; ensure the Property Search form has appeared
  (with-retry (partial retry-backoff 16)
    (el/send-text! (el/find-by-id browser "SearchFormEx1_ACSTextBox_StreetNumber") (str street-num)))
  (el/send-text! (el/find-by-id browser "SearchFormEx1_ACSTextBox_StreetName") street-name)
  (el/click! (el/find-by-xpath browser (str ".//option[text()='" (s/upper-case city) "']")))
  (el/click! (el/find-by-id browser "SearchFormEx1_btnSearch"))
  (with-retry (partial retry-backoff 16)
    ; sniff test to ensure results have loaded
    (el/find-by-xpath browser rows-xpath)))

(defn find-doc-img [browser]
  (el/find-by-xpath browser "//img[@id='ImageViewer1_docImage' and contains(@src, 'ACSResource')]"))

(defn focus-popup [browser]
  (*retry-fn* #(if (> (count (brw/all-windows browser)) 1)
                 (brw/switch-to-window browser (last (brw/all-windows browser)))
                 (throw (Exception. "Popup failed to open.")))))

(defn focus-main [browser]
  (brw/switch-to-window browser (first (brw/all-windows browser))))

(defn add-to-basket [browser row]
  ; load in right hand column
  (brw/execute-script browser (last (re-find #"javascript:(.*)" (el/attr (el/find-by-tag row "a") "href"))))
  (try
    (let [bookpage (el/text (el/find-by-css row "a[id*='Book/Page']"))] ; find-by-xpath doesn't seem to scope to passed element
      (prn (el/text row))
      (with-retry (partial retry-backoff 16)
        ; check to make sure the right hand column has loaded
        (el/find-by-xpath browser (str "//table[@id='DocDetails1_GridView_Details']//td[text()='" bookpage "']"))))
    (catch Exception e (sh "open" (el/screenshot browser))))
  ; open modal
  (brw/execute-script browser "__doPostBack('DocDetails1$ButAddToBasket','')")
  ; submit form
  (try
    (with-retry (partial retry-backoff 16)
      (el/click! (el/find-by-id browser "OrderCriteriaCtrl1_ImageButton_Next")))
    (catch Exception e (sh "open" (el/screenshot browser)))))

(defn download-basket [browser filename]
  (brw/execute-script browser "__doPostBack('Navigator1$Basket','')")
  (brw/execute-script browser "__doPostBack('BasketCtrl1$LinkButtonDownload','')")
  (-> (with-retry (partial retry-const 100 20000)
                 (focus-popup browser)
                 (el/find-by-id browser "DownloadLink"))
      (el/attr "href")
      (download filename))
  (focus-main browser))

(defn scrape []
  (let [street-num 36
        street-name "Follen"
        city "Cambridge"]
    (brw/with-browser [browser (brw/make-browser)]
      (brw/fetch! browser "http://www.masslandrecords.com/MiddlesexSouth/Default.aspx")
      (property-search browser street-num street-name city)
      (dotimes [n (count (el/find-by-xpath* browser rows-xpath))]
        (add-to-basket browser (nth (el/find-by-xpath* browser rows-xpath) n)))
      (download-basket browser (str "/Users/leppert/Downloads/" street-num "-" street-name "-" city ".zip")))))

(defn scratch []
  (scrape)
  )
