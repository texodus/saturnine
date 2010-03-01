(ns sample
  "Contains a few sample implementations to illustatre usage of the Saturnine 
   library"
  (:use [saturnine]))





;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;
;;;; Sum 

(defhandler Sum [sum]
  (upstream [msg] (let [new-sum (+ sum msg)]
                         (send-down (str "Sum is " new-sum))
                         (assoc this :sum new-sum))))

(defserver sum-server 1234 :string :clj (Sum 0))





;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;
;;;; Repl

(defserver repl-server 1235 :string :print :clj :echo) 




;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;
;;;; Chat

(def users (ref {}))

(defn write-all [ip msg]
  (doseq [user (vals (dissoc @users ip))]
    (write user msg)))

(defhandler Chat []
  (connect [] (do (dosync (alter users assoc (ip) (connection)))
                  (write-all (ip) (str "User " (ip) " connected!\r\n"))))
  (disconnect [] (do (dosync (alter users dissoc (ip)))
                     (write-all (ip) (str "User " (ip) " disconnected!\r\n"))))
  (upstream [msg] (write-all (ip) (str (ip) " : " msg))))

(defserver chat-server 4321 :string (Chat))

