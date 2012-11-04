(ns saturnine.examples-test
  (:import [java.net Socket]
	   [java.io InputStreamReader OutputStreamWriter BufferedReader BufferedWriter]
	   [clojure.lang LineNumberingPushbackReader])
  (:use [clojure.test]
        [saturnine.examples]
        [saturnine.core]
	[saturnine.internal-test]
        [clojure.tools.logging :only [info]]))




;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;
;;;; Test REPL

(deftest test-repl-juggling
  (let [server   (start-repl-server)
	socket-1 (new-sock 2222)
	socket-2 (new-sock 2222)
	write-1  (new-write socket-1)
	read-1   (new-read socket-1)
	write-2  (new-write socket-2)
	read-2   (new-read socket-2)]
    (write-2 '(+ 1 2 3))
    (is (= "=> 6" (read-2)))
    (write-2 '(info "Cause a side"))
    (is (= "=> nil" (read-2)))
    (write-1 '(def x (atom [1 2 3 4 5 6 7 8 9 0])))
    (is (= "=> #'clojure.core/x" (read-1)))
    (write-1 '(defn shuffle [a] 
		(let [b (rand-int 10) 
		      c (rand-int 10) 
		      cc (a b)] 
		  (assoc a b (a c) c cc))))
    (is (= "=> #'clojure.core/shuffle" (read-1)))
    (write-1 '(dotimes [_ 10000] (repeatedly (swap! x shuffle))))
    (write-2 '(dotimes [_ 10000] (repeatedly (swap! x shuffle))))
    (is (= "=> nil" (read-1)))
    (is (= "=> nil" (read-2)))
    (write-1 '@x)
    (is (= [0 1 2 3 4 5 6 7 8 9] (sort (read-string (apply str (drop 3 (read-1)))))))
    (.close socket-1)
    (.close socket-2)
    (stop-server server)))





;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;
;;;; Test Sum

(deftest test-sum-server
  (let [server  (start-sum-server)
	socket (new-sock 1234)
	write  (new-write socket)
	read   (new-read socket)]
    (write "4")
    (is (= "Sum is 4" (read)))
    (write "6")
    (is (= "Sum is 10" (read)))
    (.close socket)
    (stop-server server)))




;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;
;;;; Test Chat

(deftest test-chat-server
  (let [server   (start-chat-server)
	socket-1 (new-sock 3333)
	socket-2 (new-sock 3333)
	write-1  (new-write socket-1)
	read-1   (new-read socket-1)
	write-2  (new-write socket-2)
	read-2   (new-read socket-2)]
    (is (= "User connected!" (apply str (take-last 15 (read-1)))))
    (write-1 "Hello")
    (write-2 "Hi!")
    (is (= "Hello" (apply str (take-last 5 (read-2)))))
    (is (= "Hi!" (apply str (take-last 3 (read-1)))))
    (.close socket-1)
    (is (= "User disconnected!" (apply str (take-last 18 (read-2)))))
    (.close socket-2)
    (stop-server server)))

