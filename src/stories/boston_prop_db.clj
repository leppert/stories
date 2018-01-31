(ns stories.boston-prop-db
  (:require [clojure.java.shell :refer [sh]]
            [sparkledriver.browser :as brw]
            [sparkledriver.element :as el]
            [sparkledriver.retry :refer [with-retry
                                         retry-backoff]]))

; TODO - incomplete
(defn scrape
  [street-num street-name city]
  (brw/with-browser [browser (brw/make-browser)]
    (prn (str "┌ Scraping " street-num " " street-name ", " city))

    (brw/fetch! browser "https://www.cityofboston.gov/assessing/search/")
    (with-retry (partial retry-backoff 16)
      (let [form (el/find-by-xpath browser "//div[contains(@class, 'mainLeadStory')]//form")]
        (el/send-text! (el/find-by-xpath form ".//input[@type='text']") (str street-name street-num))
        (el/submit-form! form)))

    ; ensure property page loaded
    (with-retry (partial retry-backoff 16)
      (el/find-by-id browser "PropertyDetailPanel"))

    (prn "├ Downloading PDF")
    (-> (brw/current-url browser)
        (#(sh "phantomjs" "src/js/rasterize.js" % (str "/Users/leppert/Downloads/" street-num "-" street-name "-" city "Property-DB.pdf"))))))
