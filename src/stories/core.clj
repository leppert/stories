(ns stories.core
  (:require [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as s]
            [clj-http.client :as client]
            [sparkledriver.cookies :refer [browser-cookies->map]]
            [sparkledriver.browser :as brw]
            [sparkledriver.element :as el]
            [sparkledriver.retry :refer [with-retry retry-backoff *retry-fn*]]))
  
(defn download [browser url dest]
  (io/copy
   (:body (client/get url {:as :stream
                           :cookies (browser-cookies->map browser)
                           :client-params {"http.useragent" "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_13) AppleWebKit/604.1.38 (KHTML, like Gecko) Version/11.0 Safari/604.1.38"}}))
   (java.io.File. dest)))

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

(defn nav-to-property-results [browser]
  ; select Property Search
  (brw/execute-script browser "__doPostBack('Navigator1$SearchCriteria1$LinkButton03','')")

  ; ensure the Property Search form has appeared
  (with-retry (partial retry-backoff 16)
    (el/send-text! (el/find-by-id browser "SearchFormEx1_ACSTextBox_StreetNumber") "36"))
  (el/send-text! (el/find-by-id browser "SearchFormEx1_ACSTextBox_StreetName") "follen")
  (el/click! (el/find-by-xpath browser ".//option[text()='CAMBRIDGE']"))
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

(defn download-record [browser row]
  (let [filename (s/replace (el/text row) #"[\t\s/]" "-")
        bookpage (el/text (el/find-by-xpath (first (find-rows browser)) "//a[contains(@id, 'Book/Page')]"))]
    (with-retry (partial retry-backoff 16)
      (brw/execute-script browser (last (re-find #"javascript:(.*)" (el/attr (el/find-by-tag row "a") "href"))))
      ; check to make sure the right hand column has loaded
      (el/find-by-xpath browser (str "//table[@id='DocDetails1_GridView_Details']//td[text()='" bookpage "']"))
      (if (= (count (brw/all-windows browser)) 1)
        (brw/execute-script browser "__doPostBack('TabController1$ImageViewertabitem','')"))

      (focus-popup browser)
      (loop [i 0]
        (-> (find-doc-img browser)
            (el/attr "src")
            (s/replace #"(ZOOM=)(\d+)" "$16") ; 6 seems to be max zoom
            (#(download browser % (str "/Users/leppert/Downloads/follen/" filename "-PAGE-" i ".jpg"))))
        (if (first (el/find-by-css* browser "#ImageViewer1_BtnNext"))
          (do
            (el/click! (el/find-by-css browser "#ImageViewer1_BtnNext"))
            (recur (inc i))))))
    (focus-main browser)))

(defn scrape []
  (brw/with-browser [browser (brw/make-browser)]
    (brw/fetch! browser "http://www.masslandrecords.com/MiddlesexSouth/Default.aspx")
    (nav-to-property-results browser)
    (dotimes [n (count (find-rows browser))]
      (download-record browser (nth (find-rows browser) n)))))

;; (scrape)

(defn scratch []
  (def browser (brw/fetch! (brw/make-browser) "http://www.masslandrecords.com/MiddlesexSouth/Default.aspx"))
  (brw/close-browser! browser)

  (let [url "http://www.masslandrecords.com/MiddlesexSouth/ACSResource.axd?SCTTYPE=OPEN&URL=d:\\i2\\middlesexsouth\\temp\\gsmuqkiqdrkqia2hfmfipx2b\\10_13_2017_8_15_19_am\\Download.zip&EXTINFO=&RESTYPE=ZIP&ACT=DOWNLOAD"
        dest "/Users/leppert/Downloads/test.zip"]
    (:body (client/get url {:cookies {:TS6d86e7 {:domain "www.masslandrecords.com"
                                                 :path "/"
                                                 :value "e46aad3fe894b506c67b9697a46c770116ea197492192fc759dfea50dc9951a6a28dfd26"}} ; (browser-cookies->map browser)
                            })))

  (def browser (brw/fetch! (brw/make-browser) "http://www.masslandrecords.com/MiddlesexSouth/ACSResource.axd?SCTTYPE=OPEN&URL=d:\\i2\\middlesexsouth\\temp\\gsmuqkiqdrkqia2hfmfipx2b\\10_12_2017_9_51_11_am\\150753_9_18_2017.pdf&EXTINFO=&RESTYPE=PDF&ACT=PRINT"))
  (nav-to-property-results browser)
  (el/text (el/find-by-xpath (first (find-rows browser)) "//a[contains(@id, 'Book/Page')]"))
  (el/find-by-xpath browser (str "//table[@id='DocDetails1_GridView_Details']//td[text()='" bookpage "']"))
  
  (sh "open" (el/screenshot browser))
  (el/click! (el/find-by-tag (last (find-rows browser)) "a"))
  (el/click! (el/find-by-id browser "TabController1_ImageViewertabitem"))
  (brw/execute-script browser "__doPostBack('DocList1$GridView_Document$ctl02$ButtonRow_Type Desc._0','')")
  (brw/execute-script browser "__doPostBack('TabController1$ImageViewertabitem','')")
  (count (brw/all-windows browser))
  (sh "open" (el/screenshot browser))
  (focus-popup browser)
  (el/find-by-css* browser "#ImageViewer1_BtnNext")
  (.close browser)
  (el/click! (el/find-by-id browser "TabController1_ImageViewertabitem"))
  (brw/switch-to-window browser "0")
  (brw/fetch! browser "about:blank")
  (last (re-find #"javascript:(.*)" "javascript:__doPostBack('DocList1$GridView_Document$ctl17$ButtonRow_Type Desc._15','')")))
