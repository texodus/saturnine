(ns sample
  "Contains a few sample implementations to illustrate usage of the Saturnine 
   library"
  (:use [saturnine]))





;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;
;;;; Sum 

(defhandler #^{:doc
  "The Sum handler contains the cumulative sum state so far for single 
   connection.  When an upstream message is received, it is added to the sum, 
   written back to the client and then returned, thus updating the state for 
   future calls."}
  Sum [sum]
  (upstream [msg] (let [new-sum (+ sum msg)]
                         (send-down (str "Sum is " new-sum))
                         (assoc this :sum new-sum))))

(defserver #^{:doc
  "Sum is a simple server that reads a stream of newline-delimited Integers and
   responds with the cumulative sum.  It is constructed of 3 handlers:

   :string - emits strings at every new line
   :clj    - calls read & eval on each string, making \"1\" -> 1, for example.
   Sum     - The custom handler contains the cumulative sum state so far for a
             single connection.  Non-integers that are evalable will throw a
             ClassCastException to the default handle, while unevalable ones 
             will display a parse error."}
  sum-server 1234 :string :clj (Sum 0))





;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;
;;;; Repl

(defserver #{:doc
  "A simple REPL server.  Uses only the built-in handlers:

   :string - emits strings at every new line
   :print  - prints each string as it is incoming, then again as it is outgoing
   :clj    - converts the strings to clojure forms and evals them (with read-string)
   :echo   - bounces the eval'd forms back down the stack"}
  repl-server 1235 :string :print :clj :echo) 




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

