(ns stories.massland
  (:require [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as s]
            [stories.util :as util]
            [sparkledriver.browser :as brw]
            [sparkledriver.element :as el]
            [sparkledriver.retry :refer [with-retry
                                         retry-backoff
                                         *retry-fn*]]))

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
  (-> (with-retry (partial util/retry-const 100 20000)
                 (util/focus-popup browser)
                 (el/find-by-id browser "DownloadLink"))
      (el/attr "href")
      (util/curl-download filename))
  (util/focus-main browser))

(defn scrape
  [street-num street-name city]
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
  (prn "└ Complete"))
