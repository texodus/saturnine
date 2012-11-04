(ns saturnine.handler.internal
  {:skip-wiki true :doc "test"}
  (:gen-class) 
  (:import [java.net InetAddress InetSocketAddress URL]
           [java.util.concurrent Executors]
           [java.security KeyStore Security]
           [java.security.cert X509Certificate]
           [javax.net.ssl SSLContext KeyManagerFactory TrustManager SSLEngine]
           [org.jboss.netty.bootstrap ServerBootstrap ClientBootstrap]
	   [org.jboss.netty.handler.stream ChunkedWriteHandler]
	   [org.jboss.netty.handler.codec.http DefaultHttpResponse HttpResponseStatus 
	    HttpVersion HttpRequestDecoder HttpRequestEncoder HttpChunkAggregator]
           [org.jboss.netty.logging InternalLoggerFactory Log4JLoggerFactory JdkLoggerFactory CommonsLoggerFactory]
           [org.jboss.netty.channel Channels Channel ChannelPipeline SimpleChannelHandler ChannelFutureListener 
	    ChannelHandlerContext ChannelStateEvent ChildChannelStateEvent ExceptionEvent UpstreamMessageEvent 
	    DownstreamMessageEvent MessageEvent ChannelFuture]
           [org.jboss.netty.channel.socket.nio NioServerSocketChannelFactory NioClientSocketChannelFactory]
           [org.jboss.netty.channel.socket.oio OioServerSocketChannelFactory OioClientSocketChannelFactory]
           [org.jboss.netty.handler.codec.string StringEncoder StringDecoder]
	   [org.jboss.netty.handler.ssl SslHandler])
  (:require [clojure.tools.logging :as log]))





;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;
;;;; Networking Protocols

(defrecord Server [#^ServerBootstrap bootstrap channel])

(defrecord Client [#^ClientBootstrap bootstrap])

(defrecord Connection [#^ChannelHandlerContext context #^Channel channel])

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

(def ^:dynamic *connection* nil)

(defmacro wrap
  [ctx event f]
  `(binding [*connection* (new Connection ~ctx (.getChannel ~event))]
     (let [~'ip (.getRemoteAddress (:channel *connection*))]
       ~f)))

(defn listen 
  ([#^ChannelFuture fut fun]
     (.addListener fut 
		   (reify ChannelFutureListener
			  (operationComplete [_ future]
			    (if (.isSuccess future)
			      (fun)
			      (throw (Exception. "Operation failed")))))))
  ([#^ChannelFuture fut fun fail-fun]
     (.addListener fut 
		   (reify ChannelFutureListener
			  (operationComplete [_ future]
			    (if (.isSuccess future)
			      (fun)
			      (fail-fun)))))))

(defn log-ip
  [& args]
  (log/info (first args) (str (.getRemoteAddress (:channel *connection*)) ":" (apply str (rest args)))))

(defn messageReceived
  [ctx event handlers]
  (wrap ctx event 
        (let [new-state (upstream (@handlers ip) (. event getMessage))]
	  (if (not (nil? new-state))
            (dosync (alter handlers assoc ip new-state))))))

(defn writeRequested
  [ctx event handlers]
  (wrap ctx event 
        (let [new-state (downstream (@handlers ip) (.getMessage event))]
          (if (not (nil? new-state))
            (dosync (alter handlers assoc ip new-state))))))

(defn channelConnected
  [master ctx event handlers]
  (wrap ctx event 
	(let [new-state (connect master)]
	  (dosync (alter handlers assoc 
			 ip (if (nil? new-state) master new-state)))
	  (.sendUpstream ctx event))))

(defn channelDisconnected
  [ctx event handlers]
  (wrap ctx event 
        (do (disconnect (@handlers ip))
            (dosync (alter handlers dissoc ip))
            (.sendUpstream ctx event))))

(defn exception
  [ctx event handlers]
  (wrap ctx event 
        (do (let [new-state (log/error (@handlers ip) (bean (.getCause event)))]
              (if new-state (dosync (alter handlers assoc ip new-state)))))))

(defn get-channel-handler
  ; Create a ChannelHandler implementation from a defhandler
  [#^::Handler master] 
  (let [handlers (ref {})]
    (proxy [SimpleChannelHandler] []
      (messageReceived     [ctx event] (messageReceived ctx event handlers))
      (writeRequested      [ctx event] (writeRequested ctx event handlers))
      (channelConnected    [ctx event] (channelConnected master ctx event handlers))
      (channelDisconnected [ctx event] (channelDisconnected ctx event handlers))
      (exceptionCaught     [ctx event] (exception ctx event handlers)))))

(defn new-upstream
  ; Create a new instance of an UpstreamMessageEvent
  [channel msg]
  (UpstreamMessageEvent. channel msg (.getRemoteAddress channel)))

(defn new-downstream
  ; Create a new isntance of a DownstreamMessageEvent
  [channel msg] 
  (DownstreamMessageEvent. channel 
			   (Channels/succeededFuture channel)
			   msg 
			   (.getRemoteAddress channel)))

(defn log-error
  ; Log an Exception properly
  ; replaced str-join with source from clojure.contrib.str-utils by
  ; stuart sierra
  [message]
  (log/error (str (:message message) 
		   "\n            "
		   (apply str (interpose "\n            " 
                                         (:stackTrace message))))))