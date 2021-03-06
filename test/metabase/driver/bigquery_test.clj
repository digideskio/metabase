(ns metabase.driver.bigquery-test
  (:require metabase.driver.bigquery
            [metabase.models.database :as database]
            [metabase.query-processor :as qp]
            [metabase.test.data :as data]
            (metabase.test.data [bigquery :as bq-data]
                                [datasets :refer [expect-with-engine]]
                                [interface :refer [def-database-definition]])))

;; Make sure that paging works correctly for the bigquery driver when fetching a list of tables
;; Default page size is 50 so if we have more than that number of tables make sure they all show up
(def-database-definition ^:private fifty-one-different-tables
  ["birds_1"  [{:field-name "name", :base-type :TextField}] [["Rasta"] ["Lucky"]]]
  ["birds_2"  [{:field-name "name", :base-type :TextField}] [["Rasta"] ["Lucky"]]]
  ["birds_3"  [{:field-name "name", :base-type :TextField}] [["Rasta"] ["Lucky"]]]
  ["birds_4"  [{:field-name "name", :base-type :TextField}] [["Rasta"] ["Lucky"]]]
  ["birds_5"  [{:field-name "name", :base-type :TextField}] [["Rasta"] ["Lucky"]]]
  ["birds_6"  [{:field-name "name", :base-type :TextField}] [["Rasta"] ["Lucky"]]]
  ["birds_7"  [{:field-name "name", :base-type :TextField}] [["Rasta"] ["Lucky"]]]
  ["birds_8"  [{:field-name "name", :base-type :TextField}] [["Rasta"] ["Lucky"]]]
  ["birds_9"  [{:field-name "name", :base-type :TextField}] [["Rasta"] ["Lucky"]]]
  ["birds_10" [{:field-name "name", :base-type :TextField}] [["Rasta"] ["Lucky"]]]
  ["birds_11" [{:field-name "name", :base-type :TextField}] [["Rasta"] ["Lucky"]]]
  ["birds_12" [{:field-name "name", :base-type :TextField}] [["Rasta"] ["Lucky"]]]
  ["birds_13" [{:field-name "name", :base-type :TextField}] [["Rasta"] ["Lucky"]]]
  ["birds_14" [{:field-name "name", :base-type :TextField}] [["Rasta"] ["Lucky"]]]
  ["birds_15" [{:field-name "name", :base-type :TextField}] [["Rasta"] ["Lucky"]]]
  ["birds_16" [{:field-name "name", :base-type :TextField}] [["Rasta"] ["Lucky"]]]
  ["birds_17" [{:field-name "name", :base-type :TextField}] [["Rasta"] ["Lucky"]]]
  ["birds_18" [{:field-name "name", :base-type :TextField}] [["Rasta"] ["Lucky"]]]
  ["birds_19" [{:field-name "name", :base-type :TextField}] [["Rasta"] ["Lucky"]]]
  ["birds_20" [{:field-name "name", :base-type :TextField}] [["Rasta"] ["Lucky"]]]
  ["birds_21" [{:field-name "name", :base-type :TextField}] [["Rasta"] ["Lucky"]]]
  ["birds_22" [{:field-name "name", :base-type :TextField}] [["Rasta"] ["Lucky"]]]
  ["birds_23" [{:field-name "name", :base-type :TextField}] [["Rasta"] ["Lucky"]]]
  ["birds_24" [{:field-name "name", :base-type :TextField}] [["Rasta"] ["Lucky"]]]
  ["birds_25" [{:field-name "name", :base-type :TextField}] [["Rasta"] ["Lucky"]]]
  ["birds_26" [{:field-name "name", :base-type :TextField}] [["Rasta"] ["Lucky"]]]
  ["birds_27" [{:field-name "name", :base-type :TextField}] [["Rasta"] ["Lucky"]]]
  ["birds_28" [{:field-name "name", :base-type :TextField}] [["Rasta"] ["Lucky"]]]
  ["birds_29" [{:field-name "name", :base-type :TextField}] [["Rasta"] ["Lucky"]]]
  ["birds_30" [{:field-name "name", :base-type :TextField}] [["Rasta"] ["Lucky"]]]
  ["birds_31" [{:field-name "name", :base-type :TextField}] [["Rasta"] ["Lucky"]]]
  ["birds_32" [{:field-name "name", :base-type :TextField}] [["Rasta"] ["Lucky"]]]
  ["birds_33" [{:field-name "name", :base-type :TextField}] [["Rasta"] ["Lucky"]]]
  ["birds_34" [{:field-name "name", :base-type :TextField}] [["Rasta"] ["Lucky"]]]
  ["birds_35" [{:field-name "name", :base-type :TextField}] [["Rasta"] ["Lucky"]]]
  ["birds_36" [{:field-name "name", :base-type :TextField}] [["Rasta"] ["Lucky"]]]
  ["birds_37" [{:field-name "name", :base-type :TextField}] [["Rasta"] ["Lucky"]]]
  ["birds_38" [{:field-name "name", :base-type :TextField}] [["Rasta"] ["Lucky"]]]
  ["birds_39" [{:field-name "name", :base-type :TextField}] [["Rasta"] ["Lucky"]]]
  ["birds_40" [{:field-name "name", :base-type :TextField}] [["Rasta"] ["Lucky"]]]
  ["birds_41" [{:field-name "name", :base-type :TextField}] [["Rasta"] ["Lucky"]]]
  ["birds_42" [{:field-name "name", :base-type :TextField}] [["Rasta"] ["Lucky"]]]
  ["birds_43" [{:field-name "name", :base-type :TextField}] [["Rasta"] ["Lucky"]]]
  ["birds_44" [{:field-name "name", :base-type :TextField}] [["Rasta"] ["Lucky"]]]
  ["birds_45" [{:field-name "name", :base-type :TextField}] [["Rasta"] ["Lucky"]]]
  ["birds_46" [{:field-name "name", :base-type :TextField}] [["Rasta"] ["Lucky"]]]
  ["birds_47" [{:field-name "name", :base-type :TextField}] [["Rasta"] ["Lucky"]]]
  ["birds_48" [{:field-name "name", :base-type :TextField}] [["Rasta"] ["Lucky"]]]
  ["birds_49" [{:field-name "name", :base-type :TextField}] [["Rasta"] ["Lucky"]]]
  ["birds_50" [{:field-name "name", :base-type :TextField}] [["Rasta"] ["Lucky"]]]
  ["birds_51" [{:field-name "name", :base-type :TextField}] [["Rasta"] ["Lucky"]]])

(expect-with-engine :bigquery
  51
  (data/with-temp-db [db fifty-one-different-tables]
    (count (database/tables db))))


;; Test native queries
(expect-with-engine :bigquery
  [[100]
   [99]]
  (get-in (qp/process-query {:native   {:query (apply format "SELECT [%stest_data.venues.id] FROM [%stest_data.venues] ORDER BY [%stest_data.venues.id] DESC LIMIT 2;"
                                                      (repeat 3 bq-data/unique-prefix))}
                             :type     :native
                             :database (data/id)})
          [:data :rows]))
