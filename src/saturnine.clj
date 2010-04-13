(ns saturnine
  "This namespace is the public API for Saturnine;  it contains everything
   necessary to build server applications.  This should be the only namespace
   you need to import into your project"
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
        [saturnine.internal]))





;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;
;;;; Connection

(defn #^{:doc "Thread-bound connection"} conn [] *connection*)

(defn #^{:doc "Thread-bound ip address"} ip [] (.getRemoteAddress (:channel *connection*)))

(defn close
  {:doc "Closes the connection and dispatches a disconnect to the handlers.  Closes 
         the thread-bound *connection* if no connection argument is supplied"
   :arglists '([] [connection])}
  ([] {:pre [*connection*]}
     (close *connection*))
  ([{#^Channel channel :channel}]   
     (do (.close channel) nil)))

; TODO This (should?) block if the connection is not open for writes yet
(defn write 
  {:doc "Write a message to the connection, dispatching downstream function on the
         stack's handlers (last to first)."
   :arglists '([connection message])}
  ([msg] {:pre [*connection*]}
     (write *connection* msg))
  ([{#^Channel channel :channel} msg] 
     (do (.write channel msg)
	 nil)))

; TODO This blocks, fix it with a callback
(defn #^::Connection open 
  "Open a new Connection to a remote host and port; returns the new Connection"
  ([{bootstrap :bootstrap} host port]
     (Connection nil 
		 (let [fut (.connect bootstrap (InetSocketAddress. host port))]
		   (.getChannel fut)))))

(defn send-up
  "Sends a message upstream to the next handler in the stack.  Requires a 
   thread-bound *connection*"
  [msg] {:pre [*connection*]}
  (.sendUpstream (:context *connection*) (new-upstream (:channel *connection*) msg)))

(defn send-down
  "Sends a message downstream to the previous handler in the stack.  Requires a 
   thread-bound *connection*"
  [msg] {:pre [*connection*]}
  (.sendDownstream (:context *connection*) (new-downstream (:channel *connection*) msg)))

; TODO this blocks, fix it with a callback
(defn start-tls 
  "Convert the connection to SSL in STARTTLS mode (ignoring the first message if
   this is a server stack).  Requires a :starttls handler in the server or 
   client's definition"
  ([] {:pre [*connection* (.get (-> *connection* :context .getPipeline) "ssl")]}
     (start-tls *connection*))
  ([connection] 
     (let [{context :context channel :channel} connection
	   handler (.get (.getPipeline context) "ssl")]
       (log :debug (str "Starting SSL Handshake for " (.getRemoteAddress channel)))
       (.. handler 
	   (handshake channel)
	   (addListener (reify ChannelFutureListener
			       (operationComplete 
				[this future]
				(log :debug (str "SSL Handshake finished : " (.isSuccess future))))))))))





;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;
;;;; Server Definition

(defmacro defhandler 
   "This macro allows the user to define Handlers for a server.  Each handler
    represents the intermediate state of a single connection, and is structured
    like a datatype that implements a single protocol (Handler), though each 
    function implementation is optional and will be substituted for the default 
    implementation if missing.  Each function defined should return the new state
    of the connection (returning nil leaves the state unmodified), and optionally 
    pass new messages to the other handlers in the pipeline (via send-up or 
    send-down, which are automatically called for default implementations).  The
    handle functions you can define are:

      (connect [] ...)       ; Called when a new connection is made; this 
                               message is always sent upstream, no need to do so 
                               yourself, even if you override the default.
      (disconnect [] ...)    ; Called when a connection is closed; always sent
                               upstream.
      (upstream [msg] ...)   ; Called when a new message is dispatched upstream 
                               from the previous Handler.  Defaults to send-up,
                               but you must do this yourself if you implement this 
                               function!
      (downstream [msg] ...) ; Called when a new message is dispatched downstream 
                               from the next Handler.  Same as upstream, in reverse
      (error [msg] ...)      ; Called when an exception is thrown uncaught from 
                               this handler.  Defaults to logging the exception."
  [name args & handles]
  (let [syms (into #{} (map first handles))
	defaults ['(connect [] this) 
		  '(disconnect [] nil) 
		  '(upstream [msg] (saturnine/send-up msg)) 
		  '(downstream [msg] (saturnine/send-down msg))
                  '(error [msg] (do (clojure.contrib.logging/log :error msg)
                                    (doseq [el (:stacktrace msg)]
                                      (clojure.contrib.logging/log :error el))))]]
    `(deftype ~name ~args :as ~'this
       ~'clojure.lang.IPersistentMap
       Handler ~@handles
               ~@(filter identity 
			 (for [form defaults]
			   (if (not (syms (first form)))
			     form))))))

(defn start-server
  {:doc "Allows the user to define & start a server instance.  The only option
         available is :nonblocking or :blocking (not both), which configures the 
         server to use NIO.  In addition to Handlers you define yourself, defserver 
         accepts a few built in Handlers:

           :ssl      - SSL; use with params for SSLContext, ie [:ssl keystore 
                       keypassword certpassword]
           :starttls - Same as :ssl, except that a handler must call starttls once per
                       connection to switch to SSL mode. 
           :string   - translates to String (flushes on newline)
           :clj      - translates Strings to Clojure data structures with read-string
           :echo     - echos upstream messages downstream
           :print    - logs messages via clojure.contrib.logging
           :xml      - translates Strings to XML Elements (as Clojure maps)
           :json     - translates Strings to/from JSON objects (with 
                       clojure.contrib.json)
           :http     - translates Bytes to HTTP Request objects"
   :arglists '([name port options? & handlers])}
  [port options & handlers]
  (let [blocking (= options :blocking)
	handlers (if (#{:blocking :nonblocking} options)
		   handlers
		   (cons options handlers))
	bootstrap (apply (partial start-helper (empty-server blocking)) handlers)]
    (Server bootstrap (.bind bootstrap (InetSocketAddress. port)))))

(defn stop-server
  "Stops a Server instance, severs all spawnsed connections and deallcoates all
   system resources."
  [{bootstrap :bootstrap channel :channel}]
  (do (.unbind channel)
      (.releaseExternalResources bootstrap)))

(defn start-client
  {:doc "Allows the user to define & start a client instance.  Once the 
         client has been instantiated, you can create new connections with 
         (open client ip).  The only option available is :nonblocking or :blocking 
         (not both), which configures the client to use NIO.  In addition to Handlers 
         you define yourself, defclient accepts a few built in Handlers:

           :ssl      - SSL; use with params for SSLContext, ie [:ssl keystore 
                       keypassword certpassword]
           :starttls - Same as :ssl, except that a handler must call starttls once per
                       connection to switch to SSL mode. 
           :string   - translates to String (flushes on newline)
           :clj      - translates Strings to Clojure data structures with read-string
           :echo     - echos upstream messages downstream
           :print    - logs messages via clojure.contrib.logging
           :xml      - translates Strings to XML Elements (as Clojure maps)
           :json     - translates Strings to/from JSON objects (with 
                       clojure.contrib.json)
           :http     - translates Bytes to HTTP Response objects"
   :arglists '([name port options? & handlers])}
  [options & handlers]
  (let [blocking (= options :blocking)
	handlers (if (#{:blocking :nonblocking} options)
		   handlers
		   (cons options handlers))]
    (Client (apply (partial start-helper (empty-client blocking)) handlers))))

(defn stop-client
  "Deallocates a client stack's resources"
  [{bootstrap :bootstrap}]
  (.releaseExternalResources bootstrap))


