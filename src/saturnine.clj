(ns saturnine
  "This namespace is the public API for Saturnine;  it contains everything
   necessary to build server applications.  This should be the only namespace
   you need to import into your project"
  (:gen-class) 
  (:import [java.net InetAddress InetSocketAddress URL]
           [java.util.concurrent Executors]
           [org.jboss.netty.bootstrap ServerBootstrap ClientBootstrap]
           [org.jboss.netty.channel ChannelPipeline Channel SimpleChannelHandler ChannelFutureListener ChannelHandlerContext ChannelStateEvent ChildChannelStateEvent ExceptionEvent UpstreamMessageEvent DownstreamMessageEvent MessageEvent]
           [org.jboss.netty.channel.socket.nio NioServerSocketChannelFactory NioClientSocketChannelFactory]
           [org.jboss.netty.channel.socket.oio OioServerSocketChannelFactory OioClientSocketChannelFactory]
           [org.jboss.netty.handler.codec.string StringEncoder StringDecoder]
           [org.jboss.netty.logging InternalLoggerFactory Log4JLoggerFactory JdkLoggerFactory CommonsLoggerFactory]
	   [org.jboss.netty.handler.ssl SslHandler])
  (:require [clojure.contrib.logging :as logging]
	    [saturnine.internal :as internal])
  (:use [clojure.contrib.logging :only [log]]))





;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;
;;;; Connection

(def *connection* internal/*connection*)

(def *ip* internal/*ip*)

(defn close
  {:doc "Closes the connection and dispatches a disconnect to the handlers.  Closes 
         the thread-bound *connection* if no connection argument is supplied"
   :arglists '([] [connection])}
  ([] {:pre [*connection*]}
     (close *connection*))
  ([{#^Channel channel :channel}]   
     (do (.close channel) nil)))

(defn write 
  {:doc "Write a message to the connection, dispatching downstream function on the
         stack's handlers (last to first).  Writes to the thread-bound *connection* if 
         none is supplied"
   :arglists '([message] [connection message])}
  ([msg] {:pre [*connection*]}
     (write *connection* msg))
  ([{#^Channel channel :channel} msg] 
     (.write channel msg)))

(defn #^::Connection open 
  "Open a new Connection to a remote host and port; returns the new Connection"
  ([#^ClientBootstrap client host port]
     (do (log :debug (str "Opening connection to " host ":" port)) 
         (internal/Connection nil 
			      (.getPipeline client) 
			      (.connect client (InetSocketAddress. host port))))))

(defn send-up 
  "Sends a message upstream to the next handler in the stack.  Requires a 
   thread-bound *connection*"
  [msg] {:pre [*connection*]}
  (internal/send-up-internal msg))

(defn send-down
  "Sends a message downstream to the previous handler in the stack.  Requires a 
   thread-bound *connection*"
  [msg] {:pre [*connection*]}
  (internal/send-down-internal msg))

(defn start-tls 
  "Convert the connection to SSL in STARTTLS mode (ignoring the first message if
   this is a server stack).  Requires a :starttls handler in the server or 
   client's definition"
  ([] {:pre [*connection* (.get (:pipeline *connection*) "ssl")]}
     (start-tls *connection*))
  ([{#^ChannelPipeline pipeline :pipeline}] {:pre [(.get pipeline "ssl")]}
     (let [{pipeline :pipeline channel :channel} *connection*
	   handler (.get pipeline "ssl")]
       (log :debug (str "Starting SSL Handshake for " (.getRemoteAddress channel)))
       (.. handler 
	   (handshake channel)
	   (addListener (reify ChannelFutureListener
			       (operationComplete 
				[future]
				(log :debug (str "SSL Handshake finished : " (.isSuccess future))))))))))





;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;
;;;; Server

(defprotocol Bootstrap 
  (start [x] "Initialize a Server or Client")
  (stop  [x] "Stop a Server or Client"))

(extend :saturnine.internal/Server 
  Bootstrap {:start internal/start-server
	     :stop  (fn [{server :server}] (.unbind server))})

(extend :saturnine.internal/Client
  Bootstrap {:start internal/start-client
             :stop  (fn [_] nil)})





;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;
;;;; Definitions

(defmacro defhandler 
   "This macro allows the user to define Handlers for a server.  Each handler
   represents the intermediate state of a single connection, and is structured
   like a datatype that implements a single protocol (Handler), though each 
   function implementation is optional and will be substituted for the default 
   implementation if missing.  Each function defined should return the new state
   of the connection (returning nil leaves the state unmodified), and optionally 
   pass new messages to the other handlers in the pipeline (via send-up or 
   send-down, which are automatically called for default implementations)."
  [name args & handles]
  (let [syms (into #{} (map first handles))
	defaults ['(connect [] this) 
		  '(disconnect [] nil) 
		  '(upstream [msg] (~`send-up msg)) 
		  '(downstream [msg] (~`send-down msg))
                  '(error [msg] (clojure.contrib.logging/log :error msg))]]
    `(deftype ~name ~args :as ~'this
       ~'clojure.lang.IPersistentMap
       internal/Handler ~@handles
                        ~@(filter identity 
				  (for [form defaults]
				    (if (not (syms (first form)))
				      form))))))

(defmacro defserver
  {:doc "This macro allows the user to define a server instance.  You must call 
         (start name) to start the server once it's been defined.  The only option
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
  [name port options & handlers]
  (let [blocking (= options :blocking)
	handlers (if (#{:blocking :nonblocking} options)
		   handlers
		   (cons options handlers))]
    `(def ~name (internal/Server (saturnine.internal/empty-server ~blocking) ~port ~(apply vector handlers)))))

(defmacro defclient
  {:doc "This macro allows the user to define a client instance.  Once the 
         client has been instantiated, you can create new connections with 
         (open client ip).  The only option available is :nonblocking or :blocking 
         (not both), which configures the client to use NIO.  In addition to Handlers 
         you define yourself, defserver accepts a few built in Handlers:

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
  [name options & handlers]
  (let [blocking (= options :blocking)
	handlers (if (#{:blocking :nonblocking} options)
		   handlers
		   (cons options handlers))]
    `(def ~name (internal/Client (saturnine.internal/empty-client ~blocking) ~(apply vector handlers)))))
   

