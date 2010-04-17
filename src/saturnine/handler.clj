(ns saturnine.handler
  "Contains functions necessary to declare new Handlers, and a colleciton of
   useful functions which can be called from inside handlers (to either
   manipulate the state of the connection, or get information abotu the
   connection that called the Handler)"
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
        [saturnine.handler.internal]))





;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;
;;;; Handler Functions

(defn get-connection 
  "Thread-bound connection"
  [] {:pre [*connection*]}
  *connection*)

(defn get-ip
  "Thread-bound ip address"
  [] {:pre [*connection*]} 
  (.getRemoteAddress (:channel *connection*)))

(defn close
  {:doc "Closes the connection and dispatches a disconnect message to the 
         handlers sequentially upstream.  Closes the thread-bound *connection* 
         if no connection argument is supplied, optionally calls fun 
         asynchronously when the channel is closed."
   :arglists '([connection? fun?])}
  ([] {:pre [*connection*]}
     (close *connection*))
  ([conn-or-fun]
     (if (= (type conn-or-fun)
	    :saturnine.handler.internal/Connection)
       (do (.close (:channel conn-or-fun)) nil)
       (close *connection* conn-or-fun)))
  ([{#^Channel channel :channel} fun] {:pre [channel]}
     (listen (.close channel) fun)))

(defn write 
  {:doc "Write a message to the connection, dispatching :downstream function on the
         stack's handlers (last to first).  Optionally takes an explicit 
         connection, and a callback function that runs asynchronously when the
         write is completed successfully."
   :arglists '([connection? fun? message])}
  ([msg] {:pre [*connection*]}
     (write *connection* msg))
  ([conn-or-fun msg] 
     (if (= (type conn-or-fun)
	    :saturnine.handler.internal/Connection)
       (do (.write (:channel conn-or-fun) msg) nil)
       (write *connection* conn-or-fun msg)))
  ([{#^Channel channel :channel} fun msg] {:pre [channel]}
     (listen (.write channel msg) fun)))

(defn #^::Connection open 
  {:doc "Open a new Connection to a remote host and port; returns the new Connection.  
         Optionally takes a callback function that runs asynchronously on successful
         completion."
   :arglists '([client fun? host port])}
  ([{bootstrap :bootstrap} host port]
     (new saturnine.handler.internal.Connection nil (.. bootstrap 
			 (connect (InetSocketAddress. host port)) 
			 getChannel)))
  ([{bootstrap :bootstrap} fun host port] {:pre [bootstrap]}
     (new saturnine.handler.internal.Connection nil (let [fut (.connect bootstrap 
					 (InetSocketAddress. host port))]
		       (listen fut fun)
		       (.getChannel fut)))))

(defn send-up
  "Sends a message upstream to the next handler in the stack.  Requires a 
   thread-bound *connection*"
  [msg] {:pre [*connection*]}
  (.sendUpstream (:context *connection*) 
		 (new-upstream (:channel *connection*) msg)))

(defn send-down
  "Sends a message downstream to the previous handler in the stack.  Requires a 
   thread-bound *connection*"
  [msg] {:pre [*connection*]}
  (.sendDownstream (:context *connection*) 
		   (new-downstream (:channel *connection*) msg)))

; TODO does not work for client stack, must determine connection type and set flag
(defn start-tls 
  {:doc "Convert the connection to SSL in STARTTLS mode (ignoring the first message if
         this is a server stack).  Requires a :starttls handler in the server or 
         client's definition.  Optionally takes an explicit connection and/or a 
         callback function which is called asynchronously when this operation is 
         completed."
   :arglists '([connection? fun?])}
  ([] {:pre [*connection* (.get (-> *connection* :context .getPipeline) "ssl")]}
     (start-tls *connection*))
  ([conn-or-fun]
     (if (= (type conn-or-fun)
	    :saturnine.handler.internal/Connection)
       (start-tls conn-or-fun #(do nil))
       (start-tls *connection* conn-or-fun)))
  ([connection fun] {:pre [connection]}
     (let [{context :context channel :channel} connection
	   handler (.get (.getPipeline context) "ssl")]
       (log :debug (str "Starting SSL Handshake for " (.getRemoteAddress channel)))
       (listen (.handshake handler channel) fun))))






;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;
;;;; Handler Definition Macros

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
	defaults ['(connect [this] this) 
		  '(disconnect [this] nil) 
		  '(upstream [this msg] (saturnine.handler/send-up msg)) 
		  '(downstream [this msg] (saturnine.handler/send-down msg))
                  '(error [this msg] (do (clojure.contrib.logging/log :error msg)
                                    (doseq [el (:stacktrace msg)]
                                      (clojure.contrib.logging/log :error el))))]]
    `(deftype ~name ~args
       ~'clojure.lang.IPersistentMap
       Handler ~@handles
               ~@(filter identity 
			 (for [form defaults]
			   (if (not (syms (first form)))
			     form))))))