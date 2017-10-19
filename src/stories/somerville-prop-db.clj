(ns stories.somerville-prop-db
  (:require [clojure.java.shell :refer [sh]]
            [sparkledriver.browser :as brw]
            [sparkledriver.element :as el]
            [sparkledriver.retry :refer [with-retry
                                         retry-backoff]]))

(defn scrape
  [street-num street-name city]
  (brw/with-browser [browser (brw/make-browser :headless false)]
    (prn (str "┌ Scraping " street-num " " street-name ", " city))

    (brw/fetch! browser "http://gis.vgsi.com/somervillema/Search.aspx")
    (with-retry (partial retry-backoff 16)
      (el/send-text! (el/find-by-id browser "MainContent_txtSearchAddress") (str street-num " " street-name)))

    ; ensure property page loaded
    (with-retry (partial retry-backoff 16)
      (el/click! (el/find-by-xpath browser "//ul[contains(@class, 'ui-autocomplete')]//a")))

    (prn "├ Downloading PDF")
    (-> (brw/current-url browser)
        (#(sh "phantomjs" "src/js/rasterize.js" % (str "/Users/leppert/Downloads/" street-num "-" street-name "-" city "Property-DB.pdf"))))))
