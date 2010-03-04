# Saturnine #
Simple Asynchronous Network Application Library

### Overview ###

Saturnine is a Clojure library designed to facilitate rapid development of 
asynchronous network applications.  It is built on top of JBoss Netty, and 
inherits a number of features from this framework, but is designed with 
simplicity in mind:

- Sane defaults preferred over explicit configuration.

- Common functionality built-in, including Handlers for Bytes, Strings,
  (simple) streaming XML, HTTP, JSON, XMPP and Clojure forms.

- Event driven design with dead easy session state management.  Applications can
  be trivially run in blocking ("thread-per-connection") or nonblocking modes.

- SSL/TLS support (with starttls), also supports nonblocking operation.

### Installation ###

You'll need Git, Leiningen, Java, a computer of some sort, and a source of
electricity.  Install Saturnine with:

     git clone http://github.com/texodus/saturnine.git
     cd saturnine
     lein deps && lein install

... or add it to your leiningen `project.clj` ...

     :dependencies [[saturnine "0.1-SNAPSHOT"]]

... and add (:use 'saturnine) to your namespace declaration anywhere you want to
use Saturnine.

### Tutorial ###

For the API documentation, see [http://texodus.github.com/saturnine] or run

     lein autodoc

Saturnine applications are composed of Handlers - datatypes that represent the
processing state of received IO events from a network connection.  When a 
server is defined (with the defserver macro), the user must supply a name and
port #, followed by a list of Handlers (or keys for default handlers).  Here's
a simple REPL server that only uses default handlers:

    (defserver repl-server 1234 :nonblocking :string :print :clj :echo)

    (start repl-server)

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


Here's a slighlty more complex sum-calculating server that implements a 
custom Handler:

    (defhandler Sum [sum]
      (upstream [msg] (let [new-sum (+ sum msg)]
                        (send-down (str \"Sum is \" new-sum \"\\r\\n\"))
                        (assoc this :sum new-sum))))  ;; You can use "this" to refer to the whole session-state

    (defserver sum-server 1234 :blocking :string :clj (Sum 0))

    (start sum-server)

By declaring sum-server to process messages flushed from :clj with the value
(Sum 0), we are telling Saturnine to assign new Connections this state.  When
an upstream message is flushed from :clj, the upstream function on Sum is
called;  this function dispatches a new downstream message with the send-down 
function, then returns a new Sum, which becomes the new state of the connection,
and will be called on all future messages from this connection that are flushed
from :clj.

For further examples, please see the 'sample namespace.





### Changelog ###

* 3/1/10 v0.1 - Initial Release





### Planned Features ###

- UDP support
- clojure.contrib.logging/log re-binding
- Optimization 
    - Replace SimpleChannelHandler with reified ChannelHandler
    - Add support for zero-copy ChannelBuffer manipulation
- Additional Handler optional message endpoints - bind, open, etc.
- Better startup/shutdown support - add functions to forcefully close all child
  connections, share threadpools & SSLContexts, etc.
- Expose ChannelFutures through some unified callback interface - to assist in
  developing Client protocols with the same handlers.
- Better Http support, wrap netty http object in Clojure maps (or, provide
  ring implementation)
- Change :clj handler to work across newlines

    