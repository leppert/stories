(ns stories.cambridge-prop-db
  (:require [clojure.java.shell :refer [sh]]
            [sparkledriver.browser :as brw]
            [sparkledriver.element :as el]
            [sparkledriver.retry :refer [with-retry
                                         retry-backoff]]))

(defn scrape
  [street-num street-name city]
  (brw/with-browser [browser (brw/make-browser)]
    (prn (str "┌ Scraping " street-num " " street-name ", " city))

    (brw/fetch! browser "http://www.cambridgema.gov/propertydatabase")
    (with-retry (partial retry-backoff 16)
      (el/send-text! (el/find-by-id browser "bodycontent_0_txtStreetNum") (str street-num))
      ; send a return character with this; clicking submit and using el/submit-form! didn't work
      (el/send-text! (el/find-by-id browser "bodycontent_0_txtStreetName") (str street-name "\r")))

    ; ensure property page loaded
    (with-retry (partial retry-backoff 16)
      (el/find-by-id browser "PropertyDetailPanel"))

    (prn "├ Downloading PDF")
    (-> (brw/current-url browser)
        (#(sh "phantomjs" "src/js/rasterize.js" % (str "/Users/leppert/Downloads/" street-num "-" street-name "-" city "Property-DB.pdf"))))))
