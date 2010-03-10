(ns saturnine.sample
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
             will display a parse error in the :clj handler as well"}
  sum-server 1234 :string :clj :print (Sum 0))





;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;
;;;; Repl

(defserver #^{:doc
  "A simple REPL server.  Uses only the built-in handlers:

   :string - emits strings at every new line
   :print  - logs each string as it is incoming, then again as it is outgoing
   :clj    - converts the strings to clojure forms and evals them (with read-string)
   :echo   - bounces the eval'd forms back down the stack"}
  repl-server 2222 :string :print :prompt :clj :echo) 




;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;
;;;; Chat

(defn write-all 
  "Helper method for the Chat handler, writes msg to every IP in sample.users,
   except the supplied ip."
  [users msg]
  (doseq [user (vals (dissoc users (ip)))]
    (write user (str (ip) " : " msg))))

(defhandler #^{:doc
  "This Handler uses a Ref as it's state;  this allows every connection to share
   state using Clojure's native concurrency library.  The connect and disconenct
   functions are used to keep a list of every active connection, so messages 
   from one client can be forwarded to all of the others."}
  Chat [users]
  (connect [] (do (dosync (alter users assoc (ip) (conn)))
                  (write-all @users "User connected!\r\n")))
  (disconnect [] (do (dosync (alter users dissoc (ip)))
                     (write-all @users "User disconnected!\r\n")))
  (upstream [msg] (write-all @users msg)))

(defserver #^{:doc
  "chat-server is a simple telnet multi-user chat room.  Every newline-delimited 
   string message that a client sends to the server will be prepended with that 
   client's IP and written to every other connected client.

   The initial value for the Chat handler in this case is set to a Ref - thus 
   each new connection that is made will share the same Ref in their handler 
   functions.  "}
  chat-server 3333 :string :print (Chat (ref {})))





;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;
;;;; HTTP Static File Server

;; (defn error-response
;;   [status]
;;   (doto (DefaultHttpResponse. HTTP_1_1 status)
;;     (.setHeader CONTENT_TYPE "text/plain; charset=UTF-8")
;;     (.setContent (str "Failure: " status))))

;; (defn check
;;   [uri]
  

;; (defn sanitize
;;   [uri]
;;   (loop [format ["UTF-8", "ISO-8859-1"]]
;;     (if (empty? format) 
;;       nil
;;       (try (str (System/getProperty "user.dir")
;; 		File/separator
;; 		(.replace (URLDecoder/decode uri (first format)) \/ File/separatorChar))
;; 	   (catch UnsupportedEncodingException e 
;; 	     (recur (tail format)))))))

;; (defhandler HTTP-static []
;;   (messageReceived [msg] (let [msg (bean msg)]
;; 			   (if (not (= GET (:method msg))) 
;; 			     (write (error-response METHOD_NOT_ALLOWED))
;; 			     (let [path (sanitize (:uri msg))]
;; 			       (if (nil? path)
;; 				 (write (error-response FORBIDDEN))
;; 				 (let [file (File. path)]
;; 				   (if (or (.isHidden file) (not (.exists file)))
;; 				     (write (error-response NOT_FOUND))
;; 				     (if (not (.isFile file))
;; 				       (write (error-response FORBIDDEN))
;; 				       (try (let [raf (RandomAccessFile. file "r")
;; 						  response (doto (DefaultHttpResponse. HTTP_1_1 OK)
;; 							     (.setContentLength (.length raf)))]
;; 					      (write (ChunkedFile. raf 0 (.length raf) 8192)))
;; 					    (catch Exception e (write (error-response NOT_FOUND)))))))))))))