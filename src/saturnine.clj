(ns saturnine
  "This namespace contains functions for starting and stopping server and
   client instances"
  (:gen-class) 
  (:import [java.net InetAddress InetSocketAddress URL]
           [java.util.concurrent Executors]
           [org.jboss.netty.bootstrap ServerBootstrap ClientBootstrap]
           [org.jboss.netty.channel ChannelPipeline Channel SimpleChannelHandler ChannelFutureListener 
            ChannelHandlerContext ChannelStateEvent ChildChannelStateEvent ExceptionEvent UpstreamMessageEvent 
            DownstreamMessageEvent MessageEvent]
           [org.jboss.netty.channel.socket.nio NioServerSocketChannelFactory NioClientSocketChannelFactory]
           [org.jboss.netty.channel.socket.oio OioServerSocketChannelFactory OioClientSocketChannelFactory]
           [org.jboss.netty.handler.codec.string StringEncoder StringDecoder]
           [org.jboss.netty.logging InternalLoggerFactory Log4JLoggerFactory JdkLoggerFactory CommonsLoggerFactory]
	   [org.jboss.netty.handler.ssl SslHandler])
  (:require [clojure.contrib.logging :as logging])
  (:use [clojure.contrib.logging :only [log]]
        [saturnine.internal]
	[saturnine.handler]
	[saturnine.handler.internal]))






;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;
;;;; Server 

(def handler-list
     ":ssl      - SSL; use with params for SSLContext, ie [:ssl keystore 
                  keypassword certpassword]
      :starttls - Same as :ssl, except that a handler must call starttls once per
                  connection to switch to SSL mode. 
      :string   - translates to String (flushes on newline)
      :clj      - translates Strings to Clojure data structures with read-string
      :echo     - echos upstream messages downstream
      :print    - logs messages via clojure.contrib.logging
      :xml      - translates Strings to XML Elements (as Clojure maps)
      :xmpp     - translates XML Elements to XMPP Messages
      :json     - translates Strings to/from JSON objects (with 
                  clojure.contrib.json)
      :http     - translates Bytes to HTTP Request/Response maps")

(defn start-server
  {:doc (str "Allows the user to define & start a server instance.  The only option
         available is :nonblocking or :blocking (not both), which configures the 
         server to use NIO.  In addition to Handlers you define yourself, defserver 
         accepts a few built in Handlers:\n\n" handler-list)
   :arglists '([name port options? & handlers])}
  [port options & handlers]
  (let [blocking (= options :blocking)
	handlers (if (#{:blocking :nonblocking} options)
		   handlers
		   (cons options handlers))
	bootstrap (apply (partial start-helper (empty-server blocking)) handlers)]
    (new saturnine.handler.internal.Server bootstrap (.bind bootstrap (InetSocketAddress. port)))))

(defn stop-server
  {:doc "Stops a Server instance, severs all spawned connections and deallocates all
         system resources."
   :arglists '([server])}
  [{bootstrap :bootstrap channel :channel}]
  (do (.unbind channel)
      (.releaseExternalResources bootstrap)))

(defn start-client
  {:doc (str "Allows the user to define & start a client instance.  Once the 
         client has been instantiated, you can create new connections with 
         (open client ip).  The only option available is :nonblocking or :blocking 
         (not both), which configures the client to use NIO.  In addition to Handlers 
         you define yourself, defclient accepts a few built in Handlers:\n\n" handler-list)
   :arglists '([name port options? & handlers])}
  [options & handlers]
  (let [blocking (= options :blocking)
	handlers (if (#{:blocking :nonblocking} options)
		   handlers
		   (cons options handlers))]
    (new saturnine.handler.internal.Client (apply (partial start-helper (empty-client blocking)) handlers))))

(defn stop-client
  {:doc "Deallocates a client stack's resources"
   :arglists '([client])}
  [{bootstrap :bootstrap}]
  (.releaseExternalResources bootstrap))


