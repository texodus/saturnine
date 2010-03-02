(ns saturnine.internal  
  (:gen-class) 
  (:import [java.net InetAddress InetSocketAddress URL]
           [java.util.concurrent Executors]
           [java.security KeyStore Security]
           [java.security.cert X509Certificate]
           [javax.net.ssl SSLContext KeyManagerFactory TrustManager SSLEngine]
           [org.jboss.netty.bootstrap ServerBootstrap ClientBootstrap]
           [org.jboss.netty.logging InternalLoggerFactory Log4JLoggerFactory JdkLoggerFactory CommonsLoggerFactory]
           [org.jboss.netty.channel Channel SimpleChannelHandler ChannelFutureListener ChannelHandlerContext ChannelStateEvent ChildChannelStateEvent ExceptionEvent UpstreamMessageEvent DownstreamMessageEvent MessageEvent]
           [org.jboss.netty.channel.socket.nio NioServerSocketChannelFactory NioClientSocketChannelFactory]
           [org.jboss.netty.channel.socket.oio OioServerSocketChannelFactory OioClientSocketChannelFactory]
           [org.jboss.netty.handler.codec.string StringEncoder StringDecoder]
	   [org.jboss.netty.handler.ssl SslHandler])
  (:require [clojure.contrib.str-utils2 :as string]
            [clojure.contrib.logging :as logging])
  (:use [clojure.contrib.logging :only [log]]
	[saturnine.xml]))





;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;
;;;; Networking Protocols

(deftype Server [#^ServerBootstrap server #^ClientBootstrap client #^SSLContext ssl port handlers] clojure.lang.IPersistentMap)

(deftype Connection [#^ChannelHandlerContext context #^Channel channel #^MessageEvent event #^::Server server] clojure.lang.IPersistentMap)

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
  [path key-pass cert-pass]
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

(defn get-ssl-engine 
  [#^SSLContext ssl-context #^boolean client-mode]
  (doto (.createSSLEngine ssl-context)
    (.setUseClientMode client-mode)))





;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;
;;;; Netty Internal

(def *connection*)

(defn messageReceived
  [server ctx event handlers]
  (let [ip (.. event getChannel getRemoteAddress)]
    (binding [*connection* (Connection ctx (.getChannel event) event server)]
      (let [new-state (upstream (@handlers ip) (. event getMessage))]
	(if (not (nil? new-state))
	  (dosync (alter handlers assoc ip new-state)))))))

(defn writeRequested
  [server ctx event handlers]
  (let [ip (.. event getChannel getRemoteAddress)]
    (binding [*connection* (Connection ctx (.getChannel event) event server)]
      (let [new-state (downstream (@handlers ip) (.getMessage event))]
	(if (not (nil? new-state))
	  (dosync (alter handlers assoc ip new-state)))))))

(defn channelConnected
  [master server ctx event handlers]
  (let [ip (.. event getChannel getRemoteAddress)]
    (binding [*connection* (Connection ctx (.getChannel event) event server)]
      (do (let [new-state (connect master)]
            (if new-state (dosync (alter handlers assoc ip new-state)))
            (.sendUpstream ctx event))))))

(defn channelDisconnected
  [server ctx event handlers]
  (let [ip (.. event getChannel getRemoteAddress)]
    (binding [*connection* (Connection ctx (.getChannel event) event server)]
      (do (disconnect (@handlers ip))
	  (dosync (alter handlers dissoc ip))
	  (.sendUpstream ctx event)))))

(defn exception
  [server ctx event handlers]
  (let [ip (.. event getChannel getRemoteAddress)]
    (binding [*connection* (Connection ctx (.getChannel event) event server)]
      (do (let [new-state (error (@handlers ip) (bean (.getCause event)))]
            (if new-state (dosync (alter handlers assoc ip new-state))))))))

(defn get-channel-handler
  [#^::Handler master #^::Server server] 
  (let [states (ref {})]
    (proxy [SimpleChannelHandler] []
      (messageReceived     [ctx event] (messageReceived server ctx event states))
      (writeRequested      [ctx event] (writeRequested server ctx event states))
      (channelConnected    [ctx event] (channelConnected master server ctx event states))
      (channelDisconnected [ctx event] (channelDisconnected server ctx event states))
      (exceptionCaught     [ctx event] (exception server ctx event states)))))

(defn send-up-internal
  [msg]
  (let [{channel :channel context :context} *connection*
        ip (.getRemoteAddress channel)]
    (.sendUpstream context (UpstreamMessageEvent. channel msg ip))))

(defn send-down-internal
  [msg]
  (let [{channel :channel context :context event :event} *connection*] 
    (.sendDownstream context (DownstreamMessageEvent. channel 
						      (.getFuture event) 
						      msg 
						      (.getRemoteAddress channel)))))







;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;
;;;; Built-in Handlers

(def echo-handler
     (proxy [SimpleChannelHandler] []
       (writeRequested [ctx event] (.sendDownstream ctx event))
       (messageReceived  [ctx event] (.sendDownstream ctx event))
       (connect [ctx event] nil)
       (disconnect [ctx event] nil)))


(def clj-handler
     (proxy [SimpleChannelHandler] []
       (messageReceived [ctx event] (let [result (try (eval (read-string (.getMessage event)))
                                                      (catch Exception e nil))]
                                      (.sendUpstream ctx 
                                                     (UpstreamMessageEvent. (.getChannel event) 
                                                                            (if result 
                                                                              result
                                                                              (do (log :error (str (.. event getChannel getRemoteAddress) " Parse Error"))
                                                                                  "nil"))
                                                                            (.getRemoteAddress (.getChannel event))))))
       (writeRequested  [ctx event] (do (.sendDownstream ctx
                                                     (DownstreamMessageEvent. (.getChannel event)
                                                                              (.getFuture event)
                                                                              (str (print-str (.getMessage event)) "\r\n")
                                                                              (.getRemoteAddress (.getChannel event))))))
       (connect [ctx event] nil)
       (disconnect [ctx event] nil)))

(def print-handler
     (proxy [SimpleChannelHandler] []
       (messageReceived [ctx event] (do (doseq [line (string/split-lines (.getMessage event))]
                                          (log :info (str (.. event getChannel getRemoteAddress) " --> " line)))
                                        (.sendUpstream ctx event)))
       (writeRequested  [ctx event] (do (doseq [line (string/split-lines (.getMessage event))]
                                          (log :info (str (.. event getChannel getRemoteAddress) " <-- " line)))
                                        (.sendDownstream ctx event)))
       (connect [ctx event] nil)
       (disconnect [ctx event] nil)))

;; (deftype XML [] 
;;   Handler (connect    []    (xml/Element nil nil nil []))
;;           (disconnect []    nil)
;; 	  (downstream [msg] (send-down state msg))
;; 	  (upstream   [msg] (loop [tokens (rest msg) 
;;                                    {next-state :state messages :messages} (xml/parse state (first msg))]
;;                               (doseq [msg messages] (send-up state msg))
;;                               (if (not (empty? tokens))
;;                                 (recur (rest tokens)
;;                                        (xml/parse next-state (first tokens)))
;;                                 next-state))))







;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;
;;;; Startup

(defn start-helper
  [{ssl :ssl bootstrap :server handlers :handlers :as server}]
  (do (let [pipeline (. bootstrap getPipeline)]
	(doseq [s handlers]
          (cond (keyword? s) (condp = s
			       :string (doto pipeline
					 (.addLast "decoder" (StringDecoder.))
					 (.addLast "encoder" (StringEncoder.)))
			       :clj    (doto pipeline
					 (.addLast "clojure" clj-handler))
			       :print  (.addLast pipeline "print" print-handler)
			       :echo   (.addLast pipeline "echo" echo-handler))
			;       :http   (doto pipeline
			;		 (.addLast "decoder" (HttpRequestDecoder.))
			;		 (.addLast "encoder" (HttpRequestEncoder.))
			;		 (.addLast "aggregator" (HttpChunkAggregator. 65536))
			;		 (.addLast "chunkedWriter" (ChunkedWriteHandler.))))
			;       :xml    (.addLast pipeline "xml" xml-handler)
			;       :json   (.addLast pipeline "json" json-handler))
		(vector? s)    (condp = (first s)
                                 :ssl  (.addLast "pipeline" (SslHandler. ssl)))
                true           (do (.addLast pipeline 
                                             (str "handler_" (.toString s)) 
                                             (get-channel-handler s server))))))
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

(defn start-internal
  [server ssl blocking]
  (do (InternalLoggerFactory/setDefaultFactory 
       (condp = logging/*impl-name*
         "org.apache.log4j"           (Log4JLoggerFactory.)
         "java.util.logging"          (JdkLoggerFactory.)
         "org.apache.commons.logging" (CommonsLoggerFactory.)))
       (let [server (assoc server 
                     :ssl ssl 
                     :server (empty-server blocking)
                     :client (empty-client blocking))]
	(do ;(start-helper server) ;  TODO make this work for client stack
	    (assoc server :server (.bind (start-helper server) (InetSocketAddress. (:port server))))))))
	   ; server))))