(ns saturnine.core-test
  (:import [java.net Socket]
	   [java.io InputStreamReader OutputStreamWriter BufferedReader BufferedWriter]
	   [clojure.lang LineNumberingPushbackReader])
  (:use [clojure.test]
        [saturnine.core]
	[saturnine.handler]
        [clojure.tools.logging :only [error]]))




;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;
;;;; Test Client

(defhandler client-handler [a]
  (upstream [this msg] (condp = msg 
                         "=> "      (condp = @a
				      nil      (write "(+ 1 2 3)")
				      :partial (swap! a (fn [_] :success)))
			 "6\r\n"    (if (= @a nil) (swap! a (fn [_] :partial)))
                         "6\r\n=> " (swap! a (fn [_] :success))
                         (do (error (str ":" msg ":"))
                             (swap! a (fn [_] :failure))))))

(deftest test-client
  (let [lock   (atom nil)
	server (start-server 2222 :nonblocking :string [:prompt "=>"] [:print "clnt"] :clj :echo)
	client (start-client :nonblocking :string (new client-handler lock))
	chan   (open client "localhost" 2222)]
    (loop [result @lock
	   count  0]      ; Have to make the test block here until the async handler gets a result
      (do (Thread/sleep 100)
	  (cond 
	    (= :success result) (is true)
	    (= :failure result) (is false)
	    (> count 5)         (is false)
	    true                (recur @lock (inc count)))))
    (close chan)
    (stop-server server)
    (stop-client client)))

