(ns saturnine.core
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
        [saturnine.core.internal]
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

      :prompt   - Writes the supplied prompt argument appended to every 
                  downstream message (eg [:prompt \"=>\"])

      :print    - logs messages via clojure.contrib.logging.  Can be used alone,
                  or in a vector with a prompt string argument, ie [:print \"QuoteServer\"]

      :xml      - translates Strings to XML Elements (as Clojure maps).  For 
                  example, the xml string \"<test>HELLO</test>\" would become:
                       
                       {:tag :start-element :qname \"test\" :attrs {}}
                       {:tag :characters :qname \"HELLO\"}
                       {:tag :end-element :qname \"test\"}

      :json     - translates Strings to/from JSON objects (with 
                  clojure.contrib.json)

      :http     - translates Bytes to HTTP Request/Response maps

      :xmpp     - translates XMPP Stanzas into clojure maps (including a fake stanza
                  to mark stream:stream opens and closes).  For example, the xml 
                  string \"<stream:stream><iq from='test@localhost' xmlns='fake'>
                  <iq:result>TEST</iq:result></iq>\" would become:

                       {:tag :stream:stream, :attrs {}, :content []}
                       {:tag     :iq
                        :attrs   {:xmlns \"fake\" :from \"test@localhost\"}
                        :content [{:tag     :iq:result
                                   :attrs   {}
                                   :content [\"TEST\"]}]}

                  Note the resemblense to clojure.xml's output, and that stream:stream
                  messages are dispatched upstream despite the fact that they are 
                  unclosed, while the iq stanza is not dispatched until the entire 
                  stanza is received.")

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


