(defproject saturnine "0.3-SNAPSHOT" 
  :description 
  "Saturnine is a Clojure library to assist in developing server applications
   in a message-passing style.  It is built on top of JBoss Netty, and inherits 
   a number of features from this framework, including trivial configuration in
   blocking or non-blocking modes and SSL/TLS support (with starttls)."
  :dependencies [[org.clojure/clojure         "1.4.0"]
                 [org.clojure/tools.logging   "0.2.3"]
                 [org.clojure/data.json       "0.2.0"]
                 [org.jboss.netty/netty       "3.2.7.Final"]
                 [log4j/log4j                 "1.2.14"]]
  :dev-dependencies [[leiningen/lein-swank "1.1.0"]
                     [autodoc              "0.7.0"]
                     [lein-clojars         "0.5.0-SNAPSHOT"]]
  :autodoc {:name        "Saturnine"
	    :page-title  "API Documentation"
	    :web-src-dir "http://www.github.com/texodus/saturnine/tree/" 
	    :copyright   "(c) 2009, Andrew Stein"
            :load-except-list [#"internal"]}
  :namespaces [saturnine.handler saturnine.core saturnine.examples])
