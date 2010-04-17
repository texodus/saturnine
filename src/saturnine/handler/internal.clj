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
  (:require [clojure.contrib.str-utils2 :as string]
	    [clojure.contrib.json.read :as jsonr]
	    [clojure.contrib.json.write :as jsonw]
            [clojure.contrib.logging :as logging])
  (:use [clojure.contrib.logging :only [log]]))





;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;
;;;; Networking Protocols

(deftype Server [#^ServerBootstrap bootstrap channel])

(deftype Client [#^ClientBootstrap bootstrap])

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

(defmacro wrap
  [ctx event f]
  `(binding [*connection* (Connection ~ctx (.getChannel ~event))
	     log          log-ip]
     (let [~'ip (.getRemoteAddress (:channel *connection*))]
       ~f)))

(defn listen 
  ([#^ChannelFuture fut fun]
     (.addListener fut 
		   (reify ChannelFutureListener
			  (operationComplete [this future]
			    (if (.isSuccess future)
			      (fun)
			      (throw (Exception. "Operation failed")))))))
  ([#^ChannelFuture fut fun fail-fun]
     (.addListener fut 
		   (reify ChannelFutureListener
			  (operationComplete [this future]
			    (if (.isSuccess future)
			      (fun)
			      (fail-fun)))))))

(defn log-ip
  [& args]
  (log (first args) (str (.getRemoteAddress (:channel *connection*)) ":" (apply str (rest args)))))

(defn messageReceived
  [ctx event handlers]
  (wrap ctx event 
        (let [new-state (upstream (@handlers ip) (. event getMessage))]
	  (if (not (nil? new-state))
            (dosync (alter handlers assoc ip new-state))))))

; TODO This function blocks on write if connection is unopened
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
        (do (let [new-state (error (@handlers ip) (bean (.getCause event)))]
              (if new-state (dosync (alter handlers assoc ip new-state)))))))

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
			   (Channels/succeededFuture channel)
			   msg 
			   (.getRemoteAddress channel)))