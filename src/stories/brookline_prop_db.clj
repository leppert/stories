(ns stories.brookline-prop-db
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

    (brw/fetch! browser "http://apps.brooklinema.gov/assessors/propertylookup.asp")
    (with-retry (partial retry-backoff 16)
      (el/send-text! (el/find-by-xpath browser "//input[@name='address_no1']") (str street-num))
      (el/send-text! (el/find-by-xpath browser "//option") str street-name)
      (el/submit-form! (el/find-by-xpath browser "//form")))

    ; ensure property page loaded
    (with-retry (partial retry-backoff 16)
      (el/find-by-id browser "PropertyDetailPanel"))

    (prn "├ Downloading PDF")
    (-> (brw/current-url browser)
        (#(sh "phantomjs" "src/js/rasterize.js" % (str "/Users/leppert/Downloads/" street-num "-" street-name "-" city "Property-DB.pdf"))))))
