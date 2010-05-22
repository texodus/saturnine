(ns saturnine.handler-test
  (:import [java.net Socket]
	   [java.io InputStreamReader OutputStreamWriter BufferedReader BufferedWriter]
	   [clojure.lang LineNumberingPushbackReader])
  (:use [clojure.test]
        [saturnine.core]
	[saturnine.handler]
	[saturnine.internal-test]
        [clojure.contrib.logging :only [log]]))




;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;
;;;; Test JSON

(deftest test-json-server
  (let [server (start-server 1111 :string [:print "json"] :json :echo)
	socket (new-sock 1111)
	write  (new-write socket)
	read   (new-read socket)]
    (write "{\"hello\" : \"test\"}")
    (is (= "{\"hello\":\"test\"}" (read)))
    (.close socket)
    (stop-server server)))





;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;
;;;; Test XML

(deftest test-xml-server
  (let [server (start-server 1111 :string [:print "xml"] :xml :echo)
	socket (new-sock 1111)
	write  (new-write socket)
	read   (new-read socket)]
    (write "<test>HELLO</test>")
    (is (= "<test>" (read)))
    (is (= "HELLO" (read)))
    (is (= "</test>" (read)))
    (.close socket)
    (stop-server server)))





;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;
;;;; Test XMPP

(deftest test-xmpp-server
  (let [server (start-server 1111 :string [:print "xmpp"] :xml :xmpp :echo)
	socket (new-sock 1111)
	write  (new-write socket)
	read   (new-read socket)]
    (write "<stream:stream>")
    (is (= "<stream:stream>" (read)))
    (write "<iq from='test@localhost'>")
    (write "<required/>")
    (write "</iq>")
    (is (= "<iq from='test@localhost'>" (read)))
    (is (= "<required>" (read)))
    (is (= "</required>" (read)))
    (is (= "</iq>" (read)))
    (.close socket)
    (stop-server server)))






