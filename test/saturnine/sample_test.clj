(ns saturnine.sample-test
  (:import [java.net Socket]
	   [java.io InputStreamReader OutputStreamWriter BufferedReader BufferedWriter]
	   [clojure.lang LineNumberingPushbackReader])
  (:use [clojure.test]
        [saturnine.sample]
        [saturnine]))

(deftest test-repl-server
  (let [server (start repl-server)
	socket (Socket. "Localhost" (:port repl-server))
	in     (new BufferedReader (new InputStreamReader (.getInputStream socket)))
	out    (new BufferedWriter (new OutputStreamWriter (.getOutputStream socket)))
	write  (fn [msg] (do (.write out msg 0 (count msg))
			     (.newLine out)
			     (.flush out)))
	read   (fn [] (.readLine in))]
    (write "(+ 1 2 3)")
    (is (= "6" (read)))
    (write "(println \"Parse Error Test\")")
    (is (= "nil" (read)))
    (.close socket)
    (.unbind server)
    (.releaseExternalResources (:server repl-server))))

(deftest test-sum-server
  (let [server (start sum-server)
	socket (Socket. "Localhost" (:port sum-server))
	in     (new BufferedReader (new InputStreamReader (.getInputStream socket)))
	out    (new BufferedWriter (new OutputStreamWriter (.getOutputStream socket)))
	write  (fn [msg] (do (.write out msg 0 (count msg))
			     (.newLine out)
			     (.flush out)))
	read   (fn [] (.readLine in))]
    (write "4")
    (is (= "Sum is 4" (read)))
    (write "6")
    (is (= "Sum is 10" (read)))
    (.close socket)
    (.unbind server)
    (.releaseExternalResources (:server sum-server))))

(deftest test-chat-server
  (let [server   (start chat-server)
	socket-1 (Socket. "Localhost" (:port chat-server))
	socket-2 (Socket. "Localhost" (:port chat-server))
	in-1     (new BufferedReader (new InputStreamReader (.getInputStream socket-1)))
	out-1    (new BufferedWriter (new OutputStreamWriter (.getOutputStream socket-1)))
	in-2     (new BufferedReader (new InputStreamReader (.getInputStream socket-2)))
	out-2    (new BufferedWriter (new OutputStreamWriter (.getOutputStream socket-2)))
	write-1  (fn [msg] (do (.write out-1 msg 0 (count msg))
			     (.newLine out-1)
			     (.flush out-1)))
	read-1   (fn [] (.readLine in-1))
        write-2  (fn [msg] (do (.write out-2 msg 0 (count msg))
			     (.newLine out-2)
			     (.flush out-2)))
	read-2   (fn [] (.readLine in-2))]
    (is (= "User connected!" (apply str (take-last 15 (read-1)))))
    (write-1 "Hello")
    (write-2 "Hi!")
    (is (= "Hello" (apply str (take-last 5 (read-2)))))
    (is (= "Hi!" (apply str (take-last 3 (read-1)))))
    (.close socket-1)
    (is (= "User disconnected!" (apply str (take-last 18 (read-2)))))
    (.close socket-2)
    (.unbind server)
    (.releaseExternalResources (:server chat-server))))

(defserver json-server 1111 :string :print :json :echo)

(deftest test-json-server
  (let [server (start json-server)
	socket (Socket. "Localhost" (:port json-server))
	in     (new BufferedReader (new InputStreamReader (.getInputStream socket)))
	out    (new BufferedWriter (new OutputStreamWriter (.getOutputStream socket)))
	write  (fn [msg] (do (.write out msg 0 (count msg))
			     (.newLine out)
			     (.flush out)))
	read   (fn [] (.readLine in))]
    (write "{\"hello\" : \"test\"}")
    (is (= "{\"hello\":\"test\"}" (read)))
    (.close socket)
    (.unbind server)
    (.releaseExternalResources (:server json-server))))
  


    
  