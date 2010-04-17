(ns saturnine.internal
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
	    [saturnine.internal.xml :as xml]
            [clojure.contrib.logging :as logging])
  (:use [clojure.contrib.logging :only [log]]
	[saturnine.handler]
	[saturnine.handler.internal :only [get-channel-handler get-ssl-handler get-ssl-context]]))







;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;
;;;; Built-in Handlers

;;;; Stateless

(defhandler Echo []
  (upstream [this msg] (send-down msg)))

(defhandler JSON []
  (downstream [this msg] (send-down (str (jsonw/json-str msg) "\r\n")))
  (upstream   [this msg] (send-up (jsonr/read-json msg))))

(defhandler Print []
  (upstream [this msg] (do (if (string? msg)
			(doseq [line (string/split-lines msg)]
			  (log :info (str (get-ip) " --> " line)))
			(log :info (str (get-ip) " --> " msg)))
		      (send-up msg)))
  (downstream [this msg] (do (if (string? msg)
			  (doseq [line (string/split-lines msg)]
			    (log :info (str (get-ip) " <-- " line)))
			  (log :info (str (get-ip) " <-- " msg)))
			(send-down msg))))

(defhandler Prompt []
  (connect    [this]    (send-down "=> "))
  (downstream [this msg] (send-down (str msg "\r\n=> "))))

(defhandler Clj []
  (upstream [this msg] (send-up (let [result (try (eval (read-string msg))
					     (catch Exception e nil))]
			     (if result result "nil"))))
  (downstream [this msg] (send-down (str (print-str msg) "\r\n"))))

(def http-codes
     (let [fields (:fields (bean HttpResponseStatus))
	   to-key (comp keyword #(.. % getName toLowerCase (replace \_ \-)))
	   keys (map to-key fields)
	   vals (map (comp str #(.get % nil)) fields)]
       (into {} (map vec (partition 2 (interleave keys vals))))))

(defhandler HTTP []
  (upstream   [this msg] (send-up (bean msg)))
  (downstream [this msg] (send-down (doto (DefaultHttpResponse. HttpVersion/HTTP_1_1 HttpResponseStatus/OK)
				 (.setContent (:content msg))
				 (.setHeader  (:headers msg))
				 (.setStatus  (http-codes (:status msg)))))))

;;;; Stateful

(defhandler XML [state tag qname attrs]
  (upstream [this msg] (loop [tokens (rest msg) 
			 {next-state :state messages :messages} (xml/parse this (first msg))]
		    (doseq [msg messages] (send-up msg))
		    (if (not (empty? tokens))
		      (recur (rest tokens)
			     (xml/parse next-state (first tokens)))
		      next-state)))
  (downstream [this msg] (send-down (xml/emit msg))))





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
			       :string (doto pipeline
					 #_(.addLast "string-delimiter-decoder" (DelimiterBasedFrameDecoder. 8192 (Delimiters/lineDelimiter)))
					 (.addLast "string-decoder" (StringDecoder.))
					 (.addLast "string-encoder" (StringEncoder.)))
			       :clj    (.addLast pipeline "clojure" (get-channel-handler (new Clj)))
			       :print  (.addLast pipeline "print" (get-channel-handler (new Print)))
			       :echo   (.addLast pipeline "echo" (get-channel-handler (new Echo)))
		               :http   (doto pipeline
		        		 (.addLast "decoder" (HttpRequestDecoder.))
		        		 (.addLast "encoder" (HttpRequestEncoder.))
		        		 (.addLast "aggregator" (HttpChunkAggregator. 65536))
		        		 (.addLast "chunkedWriter" (ChunkedWriteHandler.))
					 (.addLast "http" (get-channel-handler (new HTTP))))
		               :xml    (.addLast pipeline "xml" (get-channel-handler (new XML nil nil nil [])))
		               :json   (.addLast pipeline "json" (get-channel-handler (new JSON)))
		               :prompt (.addLast pipeline "prompt" (get-channel-handler (new Prompt))))
		(vector? s)    (.addLast pipeline "ssl" 
                                         (get-ssl-handler (apply get-ssl-context (rest s)) false 
                                                          (condp = (first s)
                                                            :ssl      false
                                                            :starttls true)))
                true           (do (.addLast pipeline 
                                             (str "handler_" (.toString s)) 
                                             (get-channel-handler s))))))
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

