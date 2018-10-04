(ns social-wallet-api.test.transactions
  (:require [midje.sweet :refer :all]
            [ring.mock.request :as mock]
            [social-wallet-api.handler :as h]
            [auxiliary.config :refer [config-read]]
            [taoensso.timbre :as log]
            [cheshire.core :as cheshire]
            [clojure.test.check.generators :as gen]
            [midje.experimental :refer [for-all]]
            [freecoin-lib.core :as lib] 
            [clj-storage.core :as store]))

(def test-app-name "social-wallet-api-test")

(def mongo-db-only {:connection "mongo"
                    :type "db-only"})

(defn parse-body [body]
  (cheshire/parse-string (slurp body) true))

(def Satoshi (BigDecimal. "0.00000001"))
(def int16-fr8 (BigDecimal. "9999999999999999.99999999"))

(def some-from-account "some-from")
(def some-to-account "some-to-account")

(defn new-transaction-request [big-number from-account to-account]
  (h/app
   (->
    (mock/request :post "/wallet/v1/transactions/new")
    (mock/content-type "application/json")
    (mock/body  (cheshire/generate-string (merge mongo-db-only {:from-id from-account
                                                                :to-id to-account
                                                                :amount big-number
                                                                :tags ["blabla"]}))))))

(defn empty-transactions []
  (store/delete-all! (-> @h/connections :mongo :stores-m :transaction-store)))

(against-background [(before :contents (h/init
                                        (config-read social-wallet-api.test.handler/test-app-name)
                                        social-wallet-api.test.handler/test-app-name))
                     (after :contents (do
                                        (empty-transactions)
                                        (h/destroy)))] 
                    
                    (facts "Create some transactions." :slow
                           (let [sum-to-account (atom (BigDecimal. 0))]
                             (for-all
                              [rand-double (gen/double* {:min Satoshi
                                                         :max int16-fr8
                                                         :NaN? false
                                                         :infinite? false})
                               from-account gen/uuid
                               to-account gen/uuid]
                              {:num-tests 200}
                              (fact "Insert 200 transactions."
                                    (let [amount (.toString rand-double)  
                                          response (new-transaction-request (log/spy amount)
                                                                            (log/spy from-account)
                                                                            (log/spy to-account))
                                          body (parse-body (:body response))
                                          _ (swap! sum-to-account #(.add % (BigDecimal. amount)))]
                                      (:status response) => 200)
                                    (lib/count-transactions (-> @h/connections :mongo) {}) => 200))
                             (facts "Retrieving transactions limited by pagination."
                                    (fact "Retrieing results without pagination whould default to 10"
                                          (let [response (h/app
                                                          (->
                                                           (mock/request :post "/wallet/v1/transactions/list")
                                                           (mock/content-type "application/json")
                                                           (mock/body  (cheshire/generate-string mongo-db-only))))
                                                body (parse-body (:body response))] 
                                            (count body) => 10))
                                    (fact "Retrieving the first 100 transactions"
                                          (let [response (h/app
                                                          (->
                                                           (mock/request :post "/wallet/v1/transactions/list")
                                                           (mock/content-type "application/json")
                                                           (mock/body  (cheshire/generate-string (merge mongo-db-only
                                                                                                        {:page 1
                                                                                                         :per-page 100})))))
                                                body (parse-body (:body response))] 
                                            (count body) => 100))
                                    (fact "Retrieving the next 100 transactions."
                                          (let [response (h/app
                                                          (->
                                                           (mock/request :post "/wallet/v1/transactions/list")
                                                           (mock/content-type "application/json")
                                                           (mock/body  (cheshire/generate-string (merge mongo-db-only
                                                                                                        {:page 2
                                                                                                         :per-page 100})))))
                                                body (parse-body (:body response))] 
                                            (count body) => 100))
                                    (fact "Third page should be empty."
                                          (let [response (h/app
                                                          (->
                                                           (mock/request :post "/wallet/v1/transactions/list")
                                                           (mock/content-type "application/json")
                                                           (mock/body  (cheshire/generate-string (merge mongo-db-only {:page 3
                                                                                                                       :per-page 100})))))
                                                body (parse-body (:body response))] 
                                            (count body) => 0))
                                    (fact "Cannot request all 200 in the same time."
                                          (let [response (h/app
                                                          (->
                                                           (mock/request :post "/wallet/v1/transactions/list")
                                                           (mock/content-type "application/json")
                                                           (mock/body  (cheshire/generate-string (merge mongo-db-only {:page 1
                                                                                                                       :per-page 200})))))
                                                body (parse-body (:body response))] 
                                            (:error body) => "Cannot request more than 100 transactions.")))
                             (facts "Retrieving transactions using other identifiers."
                                    (let [latest-transactions (-> (h/app
                                                                   (->
                                                                    (mock/request :post "/wallet/v1/transactions/list")
                                                                    (mock/content-type "application/json")
                                                                    (mock/body  (cheshire/generate-string mongo-db-only))))
                                                                  :body
                                                                  parse-body)
                                          last-transaction (first latest-transactions)
                                          last-from-account (:from-id last-transaction)
                                          last-to-account (:to-id last-transaction)]
                                      (fact "Retrieve all transactions from last from account (should be minimum 1)."
                                            (let [response (h/app
                                                          (->
                                                           (mock/request :post "/wallet/v1/transactions/list")
                                                           (mock/content-type "application/json")
                                                           (mock/body  (cheshire/generate-string (assoc mongo-db-only :account-id last-from-account)))))
                                                body (parse-body (:body response))] 
                                              (>= (count body) 1) => true
                                              (-> body first :from-id) => last-from-account))
                                      (fact "Trying to retrieve transactions different than mongo returns an empty collection."
                                            (let [response (h/app
                                                          (->
                                                           (mock/request :post "/wallet/v1/transactions/list")
                                                           (mock/content-type "application/json")
                                                           (mock/body  (cheshire/generate-string (assoc mongo-db-only :currency "other")))))
                                                body (parse-body (:body response))] 
                                              (count body) => 0
                                              (empty? body) => true))
                                      (fact "Not applicable identifiers to mongo queries are just ignored."
                                            (let [response (h/app
                                                          (->
                                                           (mock/request :post "/wallet/v1/transactions/list")
                                                           (mock/content-type "application/json")
                                                           (mock/body  (cheshire/generate-string (merge mongo-db-only {:count 10
                                                                                                                       :from 10})))))
                                                body (parse-body (:body response))] 
                                              (count body) => 10)))))))
