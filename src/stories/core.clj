(ns stories.core
  (:require [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as s]
            [clj-http.client :as client]
            [sparkledriver.cookies :refer [browser-cookies->map]]
            [sparkledriver.browser :as brw]
            [sparkledriver.element :as el]
            [sparkledriver.retry :refer [with-retry retry-backoff *retry-fn*]]))

; via https://stackoverflow.com/a/1879961/313561
(defn try-times*
  "Executes thunk. If an exception is thrown, will retry. At most n retries
  are done. If still some exception is thrown it is bubbled upwards in
  the call chain."
  [n delay thunk]
  (loop [n n]
    (if-let [result (try
                      [(thunk)]
                      (catch Exception e
                        (when (zero? n)
                          (throw e))))]
      (result 0)
      (do
        (Thread/sleep delay)
        (recur (dec n))))))

(defmacro try-times
  "Executes body. If an exception is thrown, will retry. At most n retries
  are done. If still some exception is thrown it is bubbled upwards in
  the call chain."
  [n delay & body]
  `(try-times* ~n ~delay (fn [] ~@body)))

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
  (el/click! (el/find-by-id browser "SearchFormEx1_btnSearch")))

(defn find-rows [browser]
  (el/find-by-xpath* browser "//div[@id='DocList1_ContentContainer1']//tr//tr[.//input[@type='checkbox']]"))

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
  ; check to make sure the right hand column has loaded
  (try
    (let [bookpage (el/text (el/find-by-xpath (first (find-rows browser)) "//a[contains(@id, 'Book/Page')]"))]
      (with-retry (partial retry-backoff 16)
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
  (-> (try-times 200 100
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
      (dotimes [n 2] ; (count (find-rows browser))
        (add-to-basket browser (nth (find-rows browser) n)))
      (download-basket browser (str "/Users/leppert/Downloads/" street-num "-" street-name "-" city ".zip")))))

(defn scratch []
  (scrape)

  (def browser (brw/fetch! (brw/make-browser) "http://www.masslandrecords.com/MiddlesexSouth/Default.aspx"))
  (let [street-num 36
        street-name "Follen"
        city "Cambridge"]
    (property-search browser street-num street-name city))
  (dotimes [n 2] ; (count (find-rows browser))
    (add-to-basket browser (nth (find-rows browser) n)))
  (brw/execute-script browser "__doPostBack('Navigator1$Basket','')")
  (do
    (brw/execute-script browser "__doPostBack('BasketCtrl1$LinkButtonDownload','')")
    (-> (try-times 200 100
                   (focus-popup browser)
                   (el/find-by-id browser "DownloadLink"))
        (el/attr "href")
        (download "/Users/leppert/Downloads/tester.zip")))
  
  (sh "open" (el/screenshot browser))
  (brw/close-browser! browser)


  )
