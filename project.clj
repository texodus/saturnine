(defproject saturnine "1.0-SNAPSHOT" 
  :description 
  "Saturnine is a Clojure library to assist in developing server applications
   in a message-passing style.  It is built on top of JBoss Netty, and inherits 
   a number of features from this framework, including trivial configuration in
   blocking or non-blocking modes and SSL/TLS support (with starttls).  Here's 
   a simple REPL server:
<pre id=\"namespace-docstr\">
     (use 'saturnine)

     (defserver repl-server 1234 :string :print :clj :echo)

     (start repl-server :nonblocking)
</pre>
   Here's a slighlty more complex sum-calculating server that implements a 
   custom Handler:
<pre id=\"namespace-docstr\">
     (defhandler Sum [sum]
       (upstream [msg] (let [new-sum (+ sum msg)]
                         (send-down (str \"Sum is \" new-sum \"\\r\\n\"))
                         (assoc this :sum new-sum))))

     (defserver sum-server 1234 :string :clj (Sum 0))
</pre>"
  :repositories [["JBoss" "http://repository.jboss.org/maven2"]]
  :dependencies [[org.clojure/clojure "1.2.0-master-SNAPSHOT"]
                 [org.clojure/clojure-contrib "1.2.0-master-SNAPSHOT"]
                 [org.jboss.netty/netty "3.1.0.BETA2"]
                 [log4j/log4j "1.2.14"]]
  :dev-dependencies [[leiningen/lein-swank "1.1.0"]
                     [autodoc "0.7.0"]]
  :autodoc {:name "Saturnine", :page-title "Saturnine API Documentation"}
  :namespaces [saturnine sample])

