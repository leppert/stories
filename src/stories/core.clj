(ns stories.core
  (:require [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as s]
            [clj-http.client :as client]
            [sparkledriver.cookies :refer [browser-cookies->map]]
            [sparkledriver.browser :as brw]
            [sparkledriver.element :as el]
            [sparkledriver.retry :refer [with-retry retry-backoff]]))
  
(defn download [browser url dest]
  (io/copy
   (:body (client/get url {:as :stream
                           :cookies (browser-cookies->map browser)}))
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
  (el/click! (el/find-by-id browser "SearchFormEx1_btnSearch"))
  
  ;; go ahead and open the first window; subsequent clicks will load into it.
  (el/click! (el/find-by-css browser ".DataGridRow a"))
  (brw/execute-script browser "__doPostBack('TabController1$ImageViewertabitem','')"))

(defn find-rows [browser]
  (el/find-by-xpath* browser "//div[@id='DocList1_ContentContainer1']//tr//tr[.//input[@type='checkbox']]"))

(def browser (brw/fetch! (brw/make-browser)
                     "http://www.masslandrecords.com/MiddlesexSouth/Default.aspx"))

(nav-to-property-results browser)

(dotimes [n (count (find-rows browser))]
  (let [row (nth (find-rows browser) n)
        filename (s/replace (el/text row) #"[\t\s/]" "-")
        [main-win img-win] (into [] (brw/all-windows browser))]
    (el/click! (el/find-by-tag row "a"))
    (brw/switch-to-window browser img-win)
    (Thread/sleep 1000)
    (with-retry (partial retry-backoff 16)
      (let [page-text (el/text (el/find-by-id browser "ImageViewer1_lblPageNum"))
            num-imgs (Integer/parseInt (re-find #"\d+$" page-text))]
        (dotimes [nn num-imgs]
          (with-retry (partial retry-backoff 16)
            (-> (el/find-by-id browser "ImageViewer1_docImage")
                (el/attr "src")
                (s/replace #"(ZOOM=)(\d+)" "$16") ; 6 seems to be max zoom
                (#(download browser % (str "/Users/leppert/Downloads/follen/" filename "-PAGE-" nn ".jpg")))))
          (el/click! (el/find-by-css browser "#ImageViewer1_BtnNext, #ImageViewer1_BtnNext_Disabled")))))
    (brw/switch-to-window browser main-win)))

(sh "open" (el/screenshot browser))

(brw/close-browser! browser)
