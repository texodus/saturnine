(defproject saturnine "0.1-SNAPSHOT" 
  :description 
  "Saturnine is a Clojure library to assist in developing server applications
   in a message-passing style.  It is built on top of JBoss Netty, and inherits 
   a number of features from this framework, including trivial configuration in
   blocking or non-blocking modes and SSL/TLS support (with starttls)."
  :repositories [["JBoss" "http://repository.jboss.org/maven2"]]
  :dependencies [[org.clojure/clojure "1.2.0-master-SNAPSHOT"]
                 [org.clojure/clojure-contrib "1.2.0-master-SNAPSHOT"]
                 [org.jboss.netty/netty "3.1.5.GA"]
                 [log4j/log4j "1.2.14"]]
  :dev-dependencies [[leiningen/lein-swank "1.1.0"]
                     [autodoc "0.7.0"]
                     [lein-clojars "0.5.0-SNAPSHOT"]]
  :autodoc {:name "Saturnine"
	    :page-title "API Documentation"
	    :web-src-dir "http://www.github.com/texodus/saturnine/tree/" 
	    :copyright "(c) 2009, Andrew Stein"}
  :namespaces [saturnine saturnine.sample])

