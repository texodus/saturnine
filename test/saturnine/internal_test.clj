(ns saturnine.internal-test
  (:import [java.net Socket]
	   [java.io InputStreamReader OutputStreamWriter BufferedReader BufferedWriter]
	   [clojure.lang LineNumberingPushbackReader])
  (:use [clojure.contrib.logging :only [log]]))




;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;
;;;; Test Utils

(defn new-sock [port]
  (doto (Socket. "localhost" port) (.setSoTimeout 10000)))

(defn new-read [sock]
  (let [in (new BufferedReader
		(new InputStreamReader
		     (.getInputStream sock)))]
    (fn [] (.readLine in))))

(defn new-write [sock]
  (let [out (new BufferedWriter
		 (new OutputStreamWriter
		      (.getOutputStream sock)))]
    (fn [msg] (do (.write out (str msg) 0 (count (str msg)))
		  (.newLine out)
		  (.flush out)))))


