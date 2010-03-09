(ns saturnine.internal  
  (:gen-class) 
  (:import [java.net InetAddress InetSocketAddress URL]
           [java.util.concurrent Executors]
           [java.security KeyStore Security]
           [java.security.cert X509Certificate]
           [javax.net.ssl SSLContext KeyManagerFactory TrustManager SSLEngine]
           [org.jboss.netty.bootstrap ServerBootstrap ClientBootstrap]
           [org.jboss.netty.logging InternalLoggerFactory Log4JLoggerFactory JdkLoggerFactory CommonsLoggerFactory]
           [org.jboss.netty.channel Channels Channel ChannelPipeline SimpleChannelHandler ChannelFutureListener 
	    ChannelHandlerContext ChannelStateEvent ChildChannelStateEvent ExceptionEvent UpstreamMessageEvent 
	    DownstreamMessageEvent MessageEvent]
           [org.jboss.netty.channel.socket.nio NioServerSocketChannelFactory NioClientSocketChannelFactory]
           [org.jboss.netty.channel.socket.oio OioServerSocketChannelFactory OioClientSocketChannelFactory]
           [org.jboss.netty.handler.codec.string StringEncoder StringDecoder]
	   [org.jboss.netty.handler.ssl SslHandler])
  (:require [clojure.contrib.str-utils2 :as string]
	    [clojure.contrib.json.read :as jsonr]
	    [clojure.contrib.json.write :as jsonw]
	    [saturnine.xml :as xml]
            [clojure.contrib.logging :as logging])
  (:use [clojure.contrib.logging :only [log]]))





;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;
;;;; Networking Protocols

(deftype Server [#^ServerBootstrap server port handlers])

(deftype Client [#^ClientBootstrap client handlers])

(deftype Connection [#^ChannelHandlerContext context #^Channel channel])

(defprotocol Handler
  (upstream   [x msg] "Handle a received message") 
  (downstream [x msg] "Handle an outgoing message") 
  (connect    [x]     "Handle a new connection")
  (disconnect [x]     "Handle a disconnect")
  (error      [x msg] "Handle an error"))





;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;
;;;; SSL

(defn #^SSLContext get-ssl-context
  [#^String path #^String key-pass #^String cert-pass]
  (doto (SSLContext/getInstance "TLS")
    (.init (.getKeyManagers (doto (KeyManagerFactory/getInstance "SunX509")
                              (.init (doto (KeyStore/getInstance "JKS")
                                       (.load (ClassLoader/getSystemResourceAsStream path) 
                                              (.toCharArray key-pass)))
                                     (.toCharArray cert-pass))))
           (into-array [(proxy [TrustManager] []
                          (getAcceptedIssuers [] (make-array X509Certificate 0))
                          (checkClientTrusted [x y] nil)
                          (checkServerTrusted [x y] nil))])
           nil)))

(defn #^SslHandler get-ssl-handler 
  [#^SSLContext ssl-context client-mode starttls]
  (SslHandler. (doto (.createSSLEngine ssl-context)
		 (.setUseClientMode client-mode))
	       starttls))





;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;
;;;; Netty Internal

(def *connection* nil)

(def *ip* nil)

(defmacro wrap
  [ctx event f]
  `(binding [*connection* (Connection ~ctx (.getChannel ~event))
             *ip*         (.. ~event ~'getChannel ~'getRemoteAddress)
             log          log-ip]
     ~f))

(defn log-ip
  [& args]
  (log (first args) (str *ip* ":" (apply str (rest args)))))

(defn messageReceived
  [ctx event handlers]
  (wrap ctx event 
        (let [new-state (upstream (@handlers *ip*) (. event getMessage))]
          (if (not (nil? new-state))
            (dosync (alter handlers assoc *ip* new-state))))))

(defn writeRequested
  [ctx event handlers]
  (wrap ctx event 
        (let [new-state (downstream (@handlers *ip*) (.getMessage event))]
          (if (not (nil? new-state))
            (dosync (alter handlers assoc *ip* new-state))))))

(defn channelConnected
  [master ctx event handlers]
  (wrap ctx event 
        (do (let [new-state (connect master)]
	      (dosync (alter handlers assoc *ip* (if (nil? new-state) master new-state)))
              (.sendUpstream ctx event)))))

(defn channelDisconnected
  [ctx event handlers]
  (wrap ctx event 
        (do (disconnect (@handlers *ip*))
            (dosync (alter handlers dissoc *ip*))
            (.sendUpstream ctx event))))

(defn exception
  [ctx event handlers]
  (wrap ctx event 
        (do (let [new-state (error (@handlers *ip*) (bean (.getCause event)))]
              (if new-state (dosync (alter handlers assoc *ip* new-state)))))))

(defn get-channel-handler
  [#^::Handler master] 
  (let [handlers (ref {})]
    (proxy [SimpleChannelHandler] []
      (messageReceived     [ctx event] (messageReceived ctx event handlers))
      (writeRequested      [ctx event] (writeRequested ctx event handlers))
      (channelConnected    [ctx event] (channelConnected master ctx event handlers))
      (channelDisconnected [ctx event] (channelDisconnected ctx event handlers))
      (exceptionCaught     [ctx event] (exception ctx event handlers)))))

(defn new-upstream
  [channel msg]
  (UpstreamMessageEvent. channel msg (.getRemoteAddress channel)))

(defn new-downstream
  [channel msg] 
  (DownstreamMessageEvent. channel 
			   (Channels/future channel)
			   msg 
			   (.getRemoteAddress channel)))







;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;
;;;; Built-in Handlers

;;;; Stateless

(def echo-handler
     (proxy [SimpleChannelHandler] []
       (writeRequested [ctx event] (.sendDownstream ctx event))
       (messageReceived  [ctx event] (.sendDownstream ctx event))
       (connect [ctx event] nil)
       (disconnect [ctx event] nil)))

(def json-handler
     (proxy [SimpleChannelHandler] []
       (writeRequested 
	[ctx event] 
	(.sendDownstream ctx (new-downstream (.getChannel event)
					     (str (jsonw/json-str (.getMessage event)) "\r\n"))))
       (messageReceived 
	[ctx event] 
	(.sendUpstream ctx (new-upstream (.getChannel event)
					 (jsonr/read-json (.getMessage event)))))
       (connect [ctx event] nil)
       (disconnect [ctx event] nil)))


(def print-handler
     (proxy [SimpleChannelHandler] []
       (messageReceived [ctx event] (do (if (string? (.getMessage event))
                                          (doseq [line (string/split-lines (.getMessage event))]
                                            (log :info (str (.. event getChannel getRemoteAddress) " --> " line)))
                                          (log :info (str (.. event getChannel getRemoteAddress) " --> " (.getMessage event))))
                                        (.sendUpstream ctx event)))
       (writeRequested  [ctx event] (do (if (string? (.getMessage event))
                                          (doseq [line (string/split-lines (.getMessage event))]
                                            (log :info (str (.. event getChannel getRemoteAddress) " <-- " line)))
                                          (log :info (str (.. event getChannel getRemoteAddress) " <-- " (.getMessage event))))
                                        (.sendDownstream ctx event)))
       (connect [ctx event] nil)
       (disconnect [ctx event] nil)))

;;;; Stateful

;; TODO Should be a stateful handler that dispatches on newlines or spaces when paren
;;   nesting level is 0 AND content is buffered
(def clj-handler
     (proxy [SimpleChannelHandler] []
       (messageReceived [ctx event] (let [result (try (eval (read-string (.getMessage event)))
                                                      (catch Exception e nil))]
                                      (.sendUpstream ctx (new-upstream (.getChannel event) 
								       (if result result "nil")))))
       (writeRequested  [ctx event] (do (.sendDownstream ctx (new-downstream (.getChannel event) 
									     (str (print-str (.getMessage event)) "\r\n")))))
       (connect [ctx event] nil)
       (disconnect [ctx event] nil)))

;; (deftype XML [] 
;;   Handler (connect    []    (xml/Element nil nil nil []))
;;           (disconnect []    nil)
;; 	  (downstream [msg] (.sendDownstream (:context *connection* state msg))
;; 	  (upstream   [msg] (loop [tokens (rest msg) 
;; 				   {next-state :state messages :messages} (xml/parse state (first msg))]
;; 			      (doseq [msg messages] (send-up state msg))
;; 			      (if (not (empty? tokens))
;; 				(recur (rest tokens)
;; 				       (xml/parse next-state (first tokens)))
;; 				next-state))))







;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;
;;;; Startup

(InternalLoggerFactory/setDefaultFactory 
 (condp = logging/*impl-name*
   "org.apache.log4j"           (Log4JLoggerFactory.)
   "java.util.logging"          (JdkLoggerFactory.)
   "org.apache.commons.logging" (CommonsLoggerFactory.)))

; TODO add DelimiterBasedFrameDecoder (only for string, only applicable to Windows)
; TODO modify for use with client startup sequence
; TODO make options configurable
(defn start-helper
  [{bootstrap :server handlers :handlers :as server}]
  (do (let [pipeline (. bootstrap getPipeline)]
	(doseq [s handlers]
          (cond (keyword? s) (condp = s
			       :string (doto pipeline
					 (.addLast "decoder" (StringDecoder.))
					 (.addLast "encoder" (StringEncoder.)))
			       :clj    (doto pipeline
					 (.addLast "clojure" clj-handler))
			       :print  (.addLast pipeline "print" print-handler)
			       :echo   (.addLast pipeline "echo" echo-handler)
		       ;       :http   (doto pipeline
		       ;		 (.addLast "decoder" (HttpRequestDecoder.))
		       ;		 (.addLast "encoder" (HttpRequestEncoder.))
		       ;		 (.addLast "aggregator" (HttpChunkAggregator. 65536))
		       ;		 (.addLast "chunkedWriter" (ChunkedWriteHandler.))))
		       ;       :xml    (.addLast pipeline "xml" xml-handler)
		               :json   (.addLast pipeline "json" json-handler))
		(vector? s)    (.addLast pipeline "ssl" 
                                         (get-ssl-handler (apply get-ssl-context (rest s)) false 
                                                          (condp = (first s)
                                                            :ssl      false
                                                            :starttls true)))
                true           (do (.addLast pipeline 
                                             (str "handler_" (.toString s)) 
                                             (get-channel-handler s))))))
      (.setOption bootstrap "child.tcpNoDelay" true)
      (.setOption bootstrap "child.keepAlive" true)
      (.setOption bootstrap "tcpNoDelay" true)
      (.setOption bootstrap "keepAlive" true)
      bootstrap))

(defn empty-server
  [blocking]
  (new ServerBootstrap 
       (if blocking 
	 (new OioServerSocketChannelFactory      ; Blocking
	      (Executors/newCachedThreadPool)
	      (Executors/newCachedThreadPool))
	 (new NioServerSocketChannelFactory      ; Nonblocking
	      (Executors/newCachedThreadPool)
	      (Executors/newCachedThreadPool)))))

(defn empty-client
  [blocking]
  (new ClientBootstrap
       (if blocking
	 (new OioClientSocketChannelFactory      ; Blocking
	      (Executors/newCachedThreadPool))
	 (new NioClientSocketChannelFactory      ; Nonblocking
	      (Executors/newCachedThreadPool)
	      (Executors/newCachedThreadPool)))))

(defn start-server
  [server] {:pre [server]}
  (do (.bind (start-helper server) (InetSocketAddress. (:port server)))))
     ; server))

(defn start-client
  [client] 
  nil)