(ns saturnine.core.internal
  ; Internal namespace for core; also defines built in handlers
  (:gen-class) 
  (:import [java.util.concurrent Executors]
           [org.jboss.netty.bootstrap ServerBootstrap ClientBootstrap]
	   [org.jboss.netty.handler.stream ChunkedWriteHandler]
	   [org.jboss.netty.handler.codec.frame DelimiterBasedFrameDecoder Delimiters]
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
	    [saturnine.core.internal.xml :as xml]
	    [saturnine.core.internal.stanza :as stanza]
            [clojure.contrib.logging :as logging])
  (:use [clojure.contrib.logging :only [log]]
	[saturnine.handler]
	[clojure.contrib.str-utils :only [str-join]]
	[saturnine.handler.internal :only [get-channel-handler get-ssl-handler get-ssl-context]]))







;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;
;;;; Utils

(def http-codes (let [fields (:fields (bean HttpResponseStatus))
		      to-key (comp keyword #(.. % getName toLowerCase (replace \_ \-)))
		      keys (map to-key fields)
		      vals (map (comp str #(.get % nil)) fields)]
		  (into {} (map vec (partition 2 (interleave keys vals))))))





;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;
;;;; Stateless Handlers

;; TODO Stateless handlers should probably be semantically different from
;;      stateful ones, such that developers can choose to opt out of the state 
;;      preserving overhead

(defhandler Echo []
  "Reflects all upstream messages downstream, unaltered."
  (upstream [_ msg] (send-down msg)))

(defhandler JSON []
  "Converts upstream JSON text to Clojure Maps and vice versa.  Utilizes
   clojure.contrib.json"
  (downstream [_ msg] (send-down (str (jsonw/json-str msg) "\r\n")))
  (upstream   [_ msg] (send-up (jsonr/read-json msg))))

(defn print-helper
  "Helper fun for Print handler - factors out duplicate code"
  ([title msg]
     (print-helper title msg " "))
  ([title msg sep]
     (print-helper title msg sep #(do % nil)))
  ([title msg sep fun]
       (if (string? msg)
         (doseq [line (string/split-lines msg)]
           (log :info (str title (get-ip) sep line)))
         (log :info (str title (get-ip) sep msg)))
       (fun msg)))

(defhandler Print [title]
  "Logs all events to clojure.contrib.logging"
  (connect    [_]     (print-helper title "Connected!"))
  (disconnect [_]     (print-helper title "Disconnected!"))
  (upstream   [_ msg] (print-helper title msg " --> " send-up))
  (downstream [_ msg] (print-helper title msg " <-- " send-down))
  (error      [_ msg] (log :error 
                           (str (:message msg) "\n            "
                                (str-join "\n            " 
                                          (:stackTrace msg))))))

(defhandler Prompt [prompt]
  "Adds a prompt to each downstream message"
  (connect    [_]     (send-down (str prompt " ")))
  (downstream [_ msg] (do (send-down (str msg "\r\n" prompt " ")))))

(defhandler Clj []
  "Converts incoming strings to clojure forms and evals them"
  (upstream   [_ msg] (send-up (let [result (try (eval (read-string msg))
                                                 (catch Exception e nil))]
                                 (if result result "nil"))))
  (downstream [_ msg] (send-down (str (print-str msg) ))))

(defhandler HTTP []
  "Converts HTTP Request objects to clojure maps, and vice versa
   TODO Convert this handler to a seperate file, expose a ring adapter"
  (upstream   [this msg] (send-up (bean msg)))
  (downstream [this msg] (send-down (doto (DefaultHttpResponse. HttpVersion/HTTP_1_1 HttpResponseStatus/OK)
                                      (.setContent (:content msg))
                                      (.setHeader  (:headers msg))
                                      (.setStatus  (http-codes (:status msg)))))))





;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;
;;;; Stateful Handlers

(defhandler XML [state tag qname attrs]
  "Parse a (possibly incomplete) list of characters into a list of XML Elements"
  (upstream [this msg] (loop [tokens (rest msg) 
			      {next-state :state messages :messages} (xml/parse this (first msg))]
			 (doseq [msg messages] (send-up msg))
			 (if (not (empty? tokens))
			   (recur (rest tokens) (xml/parse next-state (first tokens)))
			   next-state)))
  (downstream [this msg] (send-down (xml/emit msg))))

(defhandler Stanza [stack current state sb depth ip]
  "Stanza consumes XML elements and groups them to make XMPP Stanzas, including
   a fake stanza type for Stream elements (which does not emit symmetrically)"
  (connect    [_]        (new Stanza nil {:tag :none, :attrs {}, :content []} :between nil 0 (get-ip)))
  (upstream   [this msg] (let [{state :state messages :messages} (stanza/parse this msg)]
			   (doseq [message messages] (send-up message))
			   state))
  (downstream [_ msg]    (doseq [message (stanza/emit msg)] (send-down message))))

			   






;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;
;;;; Startup

(InternalLoggerFactory/setDefaultFactory 
 (condp = logging/*impl-name*
   "org.apache.log4j"           (Log4JLoggerFactory.)
   "java.util.logging"          (JdkLoggerFactory.)
   "org.apache.commons.logging" (CommonsLoggerFactory.)))

(defn start-helper
  [bootstrap & handlers]
  (do (let [pipeline (. bootstrap getPipeline)]
	(doseq [s handlers]
          (cond (keyword? s) (condp = s
			       :clj    (.addLast pipeline "clojure" (get-channel-handler (new Clj)))
			       :print  (.addLast pipeline "print"   (get-channel-handler (new Print "")))
			       :echo   (.addLast pipeline "echo"    (get-channel-handler (new Echo)))
		               :xml    (.addLast pipeline "xml"     (get-channel-handler (new XML nil nil nil [])))
		               :json   (.addLast pipeline "json"    (get-channel-handler (new JSON)))
		               :prompt (.addLast pipeline "prompt"  (get-channel-handler (new Prompt "=>")))
			       :xmpp   (doto pipeline
					 (.addLast "stanza" (get-channel-handler (new Stanza nil nil nil nil nil nil)))
					 #_(.addLast "xmpp"   (get-channel-handler (new XMPP))))
			       :string (doto pipeline
                 			 (.addLast "string-decoder" (StringDecoder.))
					 (.addLast "string-encoder" (StringEncoder.)))
		               :http   (doto pipeline
		        		 (.addLast "decoder"       (HttpRequestDecoder.))
		        		 (.addLast "encoder"       (HttpRequestEncoder.))
		        		 (.addLast "aggregator"    (HttpChunkAggregator. 65536))
		        		 (.addLast "chunkedWriter" (ChunkedWriteHandler.))
					 (.addLast "http"          (get-channel-handler (new HTTP)))))
		(vector? s)  (condp = (first s)
                               :ssl      (.addLast pipeline "ssl"    (get-ssl-handler (apply get-ssl-context (rest s)) false false))
                               :starttls (.addLast pipeline "ssl"    (get-ssl-handler (apply get-ssl-context (rest s)) false true))
			       :prompt   (.addLast pipeline "prompt" (get-channel-handler (new Prompt (second s))))
                               :print    (.addLast pipeline "print"  (get-channel-handler (new Print (second s)))))
                true           (do (.addLast pipeline (str "handler_" (.toString s)) (get-channel-handler s))))))
      (.setOption bootstrap "child.tcpNoDelay" true)
      (.setOption bootstrap "child.keepAlive" true)
      (.setOption bootstrap "tcpNoDelay" true)
      (.setOption bootstrap "keepAlive" true)
      bootstrap))

(defn empty-server
  [blocking]
  (new ServerBootstrap 
       (if blocking 
	 (new OioServerSocketChannelFactory (Executors/newCachedThreadPool) (Executors/newCachedThreadPool))
	 (new NioServerSocketChannelFactory (Executors/newCachedThreadPool) (Executors/newCachedThreadPool)))))

(defn empty-client
  [blocking]
  (new ClientBootstrap
       (if blocking
	 (new OioClientSocketChannelFactory (Executors/newCachedThreadPool))
	 (new NioClientSocketChannelFactory (Executors/newCachedThreadPool) (Executors/newCachedThreadPool)))))

