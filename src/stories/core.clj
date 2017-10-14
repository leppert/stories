(ns stories.core
  (:require [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as s]
            [sparkledriver.browser :as brw]
            [sparkledriver.element :as el]
            [sparkledriver.retry :refer [with-retry
                                         retry-backoff
                                         *retry-fn*]]))

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

(defn focus-popup [browser]
  (*retry-fn* #(if (> (count (brw/all-windows browser)) 1)
                 (brw/switch-to-window browser (last (brw/all-windows browser)))
                 (throw (Exception. "Popup failed to open.")))))

(defn focus-main [browser]
  (brw/switch-to-window browser (first (brw/all-windows browser))))

(def rows-xpath "//div[@id='DocList1_ContentContainer1']//tr//tr[.//input[@type='checkbox']]")

(defn groups [browser]
  (->> (el/find-by-xpath* browser "//div[@id='Navigator1_SearchCriteria1_ControlContainer']//table//table")
       (mapv (fn [g] {:name (el/attr (el/find-by-xpath g ".//tr[1]//td") "textContent")
                     :trigger (last (re-find #"javascript:(.*)" (el/attr (el/find-by-xpath g ".//a[text()='Property Search']") "href")))}))))

(defn select-search-area
  [browser group]
  (brw/execute-script browser (:trigger group))
  ; ensure that the search has rendered
  (with-retry (partial retry-backoff 16)
    (el/find-by-xpath browser (str "//span[@id='SearchInfo1_ACSLabelParam_GroupName' and text()='" (:name group) "']"))
    (el/find-by-xpath browser (str "//span[@id='SearchInfo1_ACSLabelParam_ModelName' and text()='Property Search']"))))

(defn search
  [browser street-num street-name city]
  ; ensure the Property Search form has appeared
  (with-retry (partial retry-backoff 16)
    (el/send-text! (el/find-by-id browser "SearchFormEx1_ACSTextBox_StreetNumber") (str street-num)))
  (el/send-text! (el/find-by-id browser "SearchFormEx1_ACSTextBox_StreetName") street-name)
  (el/click! (el/find-by-xpath browser (str ".//option[text()='" (s/upper-case city) "']")))
  (el/click! (el/find-by-id browser "SearchFormEx1_btnSearch"))

  (with-retry (partial retry-backoff 16)
    ; sniff test to ensure results have loaded, or "no results" box is found
    (el/find-by-xpath browser (str rows-xpath "|//span[@id='MessageBoxCtrl1_ErrorLabel1']")))
  (count (el/find-by-xpath* browser rows-xpath)))

(defn add-row-to-basket [browser row]
  ; load in right hand column
  (brw/execute-script browser (last (re-find #"javascript:(.*)" (el/attr (el/find-by-tag row "a") "href"))))
  (try
    (let [bookpage (el/text (el/find-by-xpath row ".//a[contains(@id, 'Book/Page')]"))]
      (with-retry (partial retry-backoff 16)
        ; check to make sure the right hand column has loaded
        (el/find-by-xpath browser (str "//table[@id='DocDetails1_GridView_Details']//td[text()='" bookpage "']"))))
    (catch Exception e (sh "open" (el/screenshot browser))))
  ; open modal
  (brw/execute-script browser "__doPostBack('DocDetails1$ButAddToBasket','')")
  ; submit form
  (with-retry (partial retry-backoff 16)
    (el/click! (el/find-by-id browser "OrderCriteriaCtrl1_ImageButton_Next"))))

(defn add-rows-to-basket [browser]
  (let [row-count (count (el/find-by-xpath* browser rows-xpath))]
    (prn (str "├-- " row-count " records found"))
    (dotimes [n row-count]
      ; must reselect the row each time because they update the DOM
      (let [row (nth (el/find-by-xpath* browser rows-xpath) n)]
        (prn (str "├-- (" (+ n 1) "/" row-count ") Adding: " (s/replace (el/text row) "\t" " ")))
        (add-row-to-basket browser row)))))

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
      (prn (str "┌ Scraping " street-num " " street-name ", " city))
      (brw/fetch! browser "http://www.masslandrecords.com/MiddlesexSouth/Default.aspx")

      (doall (map (fn [group]
                    (prn (str "├ Beginning " (s/lower-case (:name group)) " search"))
                    (select-search-area browser group)
                    (search browser street-num street-name city)
                    (add-rows-to-basket browser))
                  (groups browser)))
      
      (prn "├ Downloading zip archive")
      (download-basket browser (str "/Users/leppert/Downloads/" street-num "-" street-name "-" city ".zip")))
    (prn "└ Complete")))

(defn scratch []
  (scrape)
  )
