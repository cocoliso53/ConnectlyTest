(ns connectly.handler-test
  (:require [clojure.test :refer :all]
            [ring.mock.request :as mock]
            [connectly.handler :refer :all]))


(def test-db (atom {}))

(defn reset-db! []
  (reset! test-db {}))

(deftest test-new-order!
  (reset-db!)
  (new-order! test-db 1)
  (is (= 1 (count @test-db)))
  (is (= #{:user-id :product :completed? :rating :review}
         (-> @test-db
             first
             last
             keys
             set))))

(deftest test-user-orders
  (reset-db!)
  (new-order! test-db 1)
  (new-order! test-db 2)
  (new-order! test-db 1)
  (is (= 2 (count (user-orders test-db 1))))
  (is (= 1 (count (user-orders test-db 2)))))

(deftest test-pending-order?
  (reset-db!)
  (new-order! test-db 1)
  (is (true? (pending-order? (user-orders test-db 1))))
  (save-rating! test-db 1 "5")
  (is (nil? (pending-order? (user-orders test-db 1)))))

(deftest test-pending-review?
  (reset-db!)
  (new-order! test-db 1)
  (save-rating! test-db 1 "5")
  (is (true? (pending-review? (user-orders test-db 1))))
  (save-review! test-db 1 "Great product!")
  (is (nil? (pending-review? (user-orders test-db 1)))))

(deftest test-save-rating!
  (reset-db!)
  (new-order! test-db 1)
  (save-rating! test-db 1 "5")
  (let [[_ m] (first @test-db)]
    (is (= 5 (:rating m)))
    (is (= true (:completed? m)))))

(deftest test-save-review!
  (reset-db!)
  (new-order! test-db 1)
  (save-review! test-db 1 "Great product!")
  (let [[_ m] (first @test-db)]
    (is (= "Great product!" (:review m)))))

(deftest test-app
  (testing "main route"
    (let [response (app (mock/request :get "/"))]
      (is (= (:status response) 200))))

  (testing "not-found route"
    (let [response (app (mock/request :get "/invalid"))]
      (is (= (:status response) 404))))
  
  (testing "survey route not good"
    (let [response (app (-> (mock/request :post "/survey")
                       (mock/content-type "application/json")
                       (mock/body "{\"algo\": 123}")))]
      (is (= (:status response) 400))))
  
  (testing "survey route all good"
    (let [response (app (-> (mock/request :post "/survey")
                            (mock/content-type "application/json")
                            (mock/body "{\"tg-id\": 123}")))]
      (is (= (:status response) 200)))))
