# Saturnine #
Simple Asynchronous Network Application Library

### Overview ###

Saturnine is a Clojure library designed to facilitate rapid development of 
asynchronous network applications.  It is built on top of JBoss Netty, and 
inherits a number of features from this framework, but is designed with 
simplicity in mind:

- Sane defaults preferred over explicit configuration.

- Common functionality built-in, including Handlers for Bytes, Strings,
  (simple, tagsoup style) streaming XML, JSON, HTTP and Clojure forms

- Event driven design with easy session state management.  Applications can
  be trivially run in blocking ("thread-per-connection") or nonblocking modes.

- SSL/TLS support (with starttls), also supports nonblocking operation.

- clojure.contrib.logging integration (is this a feature?)

### Installation ###

You'll need Git, Leiningen, Java, a computer of some sort, and a source of
electricity.  Install Saturnine with:

     git clone http://github.com/texodus/saturnine.git
     cd saturnine
     lein install

... or add it to your leiningen `project.clj` ...

     :dependencies [[saturnine "0.2"]]

... or your maven pom.xml ...

     <repositories>
       <repository>
         <id>clojars.org</id>
         <url>http://clojars.org/repo</url>
       </repository>
     </repositories>

     <dependencies>
       <dependency>
         <groupId>saturnine</groupId>
         <artifactId>saturnine</artifactId>
         <version>0.2</version>
       </dependency>
     </dependencies>

... and add (:use 'saturnine) to your namespace declaration anywhere you want to
use Saturnine.

### Tutorial ###

Saturnine applications are composed of Handlers - datatypes that represent the
processing state of received IO events from a network connection.  When a 
server is defined (with the start-server function), the user must supply a 
port #, followed by a list of Handlers (or keys for default handlers) that 
define the processing stack for incoming messages.  Here's
a simple REPL server that only uses default handlers:

    (use 'saturnine)

    (start-server 1234 :nonblocking :string :print :clj :echo)

This starts a server listening on port 1234.  Once a connection is opened to
this server, incoming data is processed in the following manner:


1. :string - Incoming data is converting to string data and flushed to the next 
   handler when a newline is encountered.

2. :print - Incoming strings are logged via clojure.contrib.logging, then 
   flushed to the next handler.

3. :clj - Incoming strings are processed by the Clojure's read-string  and 
   eval'd, then the resulting forms are flushed to the next handler

4. :echo - echo's every incoming Clojure form, back down the stack in reverse.

5. :clj - the now-outbound Clojure form is flushed as a string with print-str

6. :print - outbound strings are logged via clojure.contrib.logging, then 
   flushed

7. :string - converts incoming Strings back into Bytes to send to the Connection


Here's a slighty more complex sum-calculating server that implements a 
custom Handler:

    (use 'saturnine 'saturnine.handler)

    (defhandler Sum [sum]
      (upstream [this msg] (let [new-sum (+ sum msg)]
                             (send-down (str "Sum is " new-sum "\r\n"))
                             (assoc this :sum new-sum))))  ; You can use "this" to refer to the whole session-state

    (start-server 1234 :blocking :string :clj (Sum 0))

By declaring sum-server to process messages flushed from :clj with the value
(Sum 0), we are telling Saturnine to assign new Connections this state.  When
an upstream message is flushed from :clj, the upstream function on Sum is
called;  this function dispatches a new downstream message with the send-down 
function, then returns a new Sum, which becomes the new state of the connection,
and will be called on all future messages from this connection that are flushed
from :clj.

For further examples, please see the [API Documentation](http://texodus.github.com/saturnine) and 
['saturnine.examples](http://github.com/texodus/saturnine/tree/master/src/saturnine/examples.clj) namespace.





### Planned Features ###

Next Release (v0.3)

- UDP support
- XMPP Handler
- Optimization 
    - Replace SimpleChannelHandler with reified ChannelHandler
    - Add support for zero-copy ChannelBuffer manipulation
- Additional Handler optional message endpoints - bind, open, etc.
- Better HTTP support in the form of a ring adapter.
- Open to suggestions ....



    