(ns saturnine
  "This namespace is the public API for Saturnine;  it contains everything
   necessary to build server applications.  This should be the only namespace
   you need to import into your project"
  (:gen-class) 
  (:import [java.net InetAddress InetSocketAddress URL]
           [java.util.concurrent Executors]
           [org.jboss.netty.bootstrap ServerBootstrap ClientBootstrap]
           [org.jboss.netty.channel Channel SimpleChannelHandler ChannelFutureListener ChannelHandlerContext ChannelStateEvent ChildChannelStateEvent ExceptionEvent UpstreamMessageEvent DownstreamMessageEvent MessageEvent]
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

(defn close
  "Closes the connection and dispatch a :disconnect to the handlers.  Closes 
   the thread-bound *connection* if no connection argument is supplied"
  ([] {:pre [*connection*]}
     (close *connection*))
  ([#^::Connection {channel :channel}]   
     (do (.close channel) nil)))

(defn connection
 "Gets the current conenction object for operation form other threads"
 []
 *connection*)

(defn write 
  "Write a message to the connection (dispatches :downstream).  Writes to the 
   thread-bound *connection* if none is supplied"
  ([msg] {:pre [*connection*]}
     (write *connection* msg))
  ([#^::Connection {channel :channel} msg] 
     (.write channel msg)))

(defn open 
  "Open a new Connection to a remote host and port.  Uses settings from thread-
   bound *connection* if none is supplied"
  ([host port] {:pre [*connection*]}
     (open (-> *connection* :server :client) host port))
  ([#^Server {client :client} host port]
     (do (log :debug (str "Opening connection to " host ":" port)) 
         (.connect client (InetSocketAddress. host port)))))

(defn ip 
  "Gets a Connection's IP.  Uses thread-bound *connection* if none supplied"
  ([] {:pre [*connection*]}
     (ip *connection*))
  ([#^::Connection {channel :channel}]
     (.getRemoteAddress channel)))

(defn send-up 
  "Sends a message upstream (dispatches :upstream).  Requires a thread-bound 
   *connection*"
  [msg] {:pre [*connection*]}
  (send-up-internal msg))

(defn send-down
  "Sends a message downstream (dispatches :downstream).  Requires a thread-bound
   *connection*"
  [msg] {:pre [*connection*]}
  (send-down-internal msg))

(defn start-tls 
  "Convert the connection to TLS in STARTTLS mode (ignoring the first message).
   Requires a thread-bound *connection*"
  [] {:pre [*connection*]}
  (let [{{ssl-context :ssl-context} :server context :context channel :channel} *connection*
        handler (SslHandler. (get-ssl-engine ssl-context false) true)]
    (log :debug (str "Starting SSL Handshake for " (.getRemoteAddress channel)))
    (do (.. context getPipeline (addFirst "ssl" handler))
        (.. handler 
            (handshake channel)
            (addListener (reify ChannelFutureListener
                                (operationComplete 
                                 [future]
                                 (log :debug (str "SSL Handshake finished : " (.isSuccess future))))))))))





;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;
;;;; Server

(defn #^::Server start 
  "Initialize all server, client, ssl & logging functionality"
  ([server] 
     (start-internal server nil true))
  ([server blocking]
     (start-internal server nil false))
  ([server keystore keypassword certificatepassword] 
     (start-internal server (get-ssl-context keystore keypassword certificatepassword) true))
  ([server blocking keystore keypassword certificatepassword] 
     (start-internal server (get-ssl-context keystore keypassword certificatepassword) false)))

(defn stop
  "Stop all server & client listeners"
  [#^::Server {server :server client :client}]
  (do (.unbind server)
      #_(.stop client)))





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
  [name args & funs]
  (let [syms (into #{} (map first funs))
	defaults ['(connect [] this) 
		  '(disconnect [] nil) 
		  '(upstream [msg] (~`send-up msg)) 
		  '(downstream [msg] (~`send-down msg))
                  '(error [msg] (clojure.contrib.logging/log :error msg))]]
    `(deftype ~name ~args :as ~'this
       ~'clojure.lang.IPersistentMap
       Handler ~@funs
               ~@(filter identity 
                         (for [form defaults]
                           (if (not (syms (first form)))
                             form))))))

(defmacro defserver
  "This macro allows the user to define a server instance.  You must call 
   (start name) to start the server once it's been defined.  In addition to 
   Handlers you define yourself, defserver accepts a few built in Handlers:

     :ssl    - SSL
     :string - translates to String (flushes on newline)
     :clj    - translates Strings to Clojure data structures with read-string
     :echo   - echos upstream messages downstream
     :print  - logs messages via clojure.contrib.logging
     :xml    - translates Strings to XML Elements (as Clojure maps)
     :json   - translates Strings to/from JSON objects (with clojure.contrib.json)
     :http   - translates Bytes to HTTP Request/Response objects (SOON TO BE CHANGED)"
  [name port & handlers]
  `(def ~name (Server nil nil nil ~port ~(apply vector handlers))))

