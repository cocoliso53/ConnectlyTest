(ns connectly.handler
  (:require [compojure.core :refer :all]
            [compojure.route :as route]
            [morse.polling :as p]
            [morse.api :as tg] 
            [morse.handlers :as h]
            [selmer.parser :as parser]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.json :refer [wrap-json-body]]
            [ring.middleware.defaults :refer [wrap-defaults site-defaults]]))

;; Assumptions: 
;; - Only one order per user at a time
;; - :user-id is the same as thelegram chat id


;;; Fake DB ;;;

;; Products ids
(def products [11 12 13 14 15])
;; atom playing as db (will restart on evey new deploy)
(def db (atom {}))

(defn user-orders [db tg-id]
  (filter (fn [[_ {user-id :user-id}]] (= tg-id user-id)) @db))

(defn pending-order? [coll]
  (some (fn [[_ {completed? :completed?}]] (not completed?)) coll))

(defn pending-review? [coll]
  (some (fn [[_ {review :review}]] (nil? review)) coll))

(defn new-order! [db tg-id]
  (swap! db #(merge % {(rand-int 100) {:user-id (if (integer? tg-id) tg-id (Integer/parseInt tg-id))
                                       :product (rand-nth products)
                                       :completed? false 
                                       :rating nil 
                                       :review nil}})))

(defn save-rating! [db tg-id score]
  (let [[[order-uuid m]] (filter (fn [[_ {rating :rating user-id :user-id}]] 
                                   (and (nil? rating) 
                                        (= user-id tg-id))) @db)
        new-m (update m :rating (constantly (Integer/parseInt score)))
        final-m (update new-m :completed? (constantly true))]
    (swap! db #(update % order-uuid (constantly final-m)))))

(defn save-review! [db tg-id text]
  (let [[[order-uuid m]] (filter (fn [[_ {review :review user-id :user-id}]] 
                                 (and (nil? review) 
                                      (= user-id tg-id))) @db)
        new-m (update m :review (constantly text))]
    (swap! db #(update % order-uuid (constantly new-m)))))

;;; Telegram functionality ;;;

(def token "6000699537:AAHw3ZE5BEjwRw-9o_xJ_UCBZF5uFt-3qZ0")
(def agradecimientos ["Thanks" "thanks" "Thank" "thank" "Gracias" "gracias"])

(tg/set-webhook token "https://plankton-app-qjlcy.ondigitalocean.app/handler")

(defn inline-keyboard [options]
  (let [keyboard-buttons (mapv (fn [option]
                                 [{:text option
                                   :callback_data option}])
                               options)]
    {:inline_keyboard keyboard-buttons}))

(defn message-handler [{{id :id :as chat} :chat :as message}]
  (let [orders (user-orders db id)
        text (:text message)
        grax? (some (fn [word] (.contains agradecimientos word))
                    (clojure.string/split (:text message) #"\s+"))]
    (if (empty? orders)
      (do
        (new-order! db id)
        (tg/send-text token id "New order created"))
      (if (pending-order? orders)
        (if grax?
          (do
            (println "Agradecimiento")
            (tg/send-text token id {:reply_markup (inline-keyboard ["5" "4" "3" "2" "1"])} 
                          "Please reate your experience (5 is the highest"))
          (do
            (println "Non thankful message: " message)
            (tg/send-text token id "This is a fake conversation, just pretend we are talking\nWhen ready just say 'Thank you'")))
        (if (pending-review? orders)
          (do
            (save-review! db id text)
            (tg/send-text token id "Thanks! Review was saved succesfully"))
          (do
            (new-order! db id)
            (tg/send-text token id "New order created")))))))

(defn finished-tx-review [{tg-id :tg-id}]
  (println "Acabó la compra")
  (new-order! db tg-id)
  (tg/send-text token tg-id {:reply_markup (inline-keyboard ["5" "4" "3" "2" "1"])}
                "Rate your experience! (5 is the highest score)"))


(h/defhandler bot-api
  (h/command-fn "start" (fn [{{id :id :as chat} :chat}]
                          (println "Bot joined new chat: " chat)
                          (tg/send-text token id "Welcome"))) 
  (h/command "help" {{id :id :as chat} :chat}
             (println "Help was requested in " chat)
             (tg/send-text token id (str "Your chat id is: " id)))
  (h/callback-fn (fn [{id :id data :data {chat :chat} :message :as full-data}]
                   (println "Data: " full-data)
                   (tg/answer-callback token id)
                   (save-rating! db (:id chat) data)
                   (tg/send-text token (:id chat) (str "You picked: " data "\n Please leave a review:"))))
  (h/message-fn message-handler))


;;(def channel (p/start token bot-api))
;;(println channel)


;;; ---------------------- ;;;

;;; Selmer HTML ;;;

(defn prepare-data [coll]
  (into []
        (map (fn [[order-uuid {:keys [user-id product completed? rating review]}]]
               {:order-uuid order-uuid :user-id user-id 
                :product product :completed? completed? 
                :rating rating :review review})
             coll)))

(defn best-products [coll]
  (->> coll
       (prepare-data)
       (filter :rating)
       (group-by :product)
       (map (fn [[product orders]]
              [product (as-> orders $
                            (map :rating $)
                            (reduce + $)
                            (/ $ (count orders)))]))
       (sort-by second >)
       (take 3)))

(defn index-page [request]
  (parser/render-file "./templates/index.html" {:items (prepare-data @db)
                                                :best (best-products @db)}))



;;;  API routes ;;;

(defroutes app-routes
  (GET "/" [request] (index-page request))
  (POST "/handler" {body :body :as r} (bot-api body))
  (POST "/survey" {body :body :as r} (if (not (nil? (:tg-id body)))
                                       (do
                                         (finished-tx-review body) 
                                         {:status 200 :body "Params ok"})
                                       {:status 400 :body "Missing 'tg-id param'"}))
  (route/not-found "Not Found"))

(def app
  (-> (routes app-routes)
      (wrap-params)
      (wrap-json-body {:keywords? true})))