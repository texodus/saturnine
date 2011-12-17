# Saturnine #
Simple Asynchronous Network Application Library

### Overview ###

Saturnine is a Clojure library designed to facilitate rapid development of
asynchronous network applications.  It is built on top of JBoss Netty, and
inherits a number of features from this framework, but is designed with
simplicity in mind:

- Sane defaults preferred over explicit configuration.

- Common functionality built-in, including Handlers for Bytes, Strings,
  (simple, tagsoup style) streaming XML, JSON, HTTP, XMPP and Clojure forms

- Event driven design with easy session state management.  Applications can
  be trivially run in blocking ("thread-per-connection") or nonblocking modes,
  without modifying your application code.

- SSL/TLS support (with starttls), also supports nonblocking operation.

A full rundown of Saturnine's functionality can be found in the [API Documentation](http://texodus.github.com/saturnine)

### Installation ###

You'll need Git, Leiningen, Java, a computer of some sort, and a source of
electricity.  Install Saturnine with:

     git clone http://github.com/texodus/saturnine.git
     cd saturnine
     lein install

... or add it to your leiningen `project.clj` ...

     :dependencies [[saturnine "0.3"]]

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
         <version>0.3</version>
       </dependency>
     </dependencies>

... and add (:use 'saturnine.core) to your namespace declaration anywhere you want to
use Saturnine.

### Tutorial ###

Saturnine is an asynchronous framework, which means simply that all operations
return nil immediately when invoked, and the result of an operation is passed
to a supplied callback function whenever the operation is completed.  In this way,
you can design applications that continue to process, even after initiating costly
IO operations.  And that's A Good Thing (c).

Saturnine applications (servers ant clients) are composed of a list of sequential
Handlers.  Each handler, in turn, is simply a clojure defrecord which implements
the Handler protocol.  The Handler defrecord itself represents the intermediate
state of the processed stream - when new data is received from a connection, saturnine
will call the relevent function from the Handler protocol on the first handler in the
list, which in turn calls the next handler and returns a new instance of itself that
represents the new state of the connection, and so on.

Data received from a conenction proceeds calls the first handler's upstream implementation
- this handler is responsible for calling (send-up ...) to pass data to the next
handler in the list (though if this function is not implemented for a Handler, the
default implementation will simply call send-up on the unprocessed data it received).
When data is written to a connection, the last handler's downstream function is called,
and utilizing (send-down ...) within this handler will pass processed data to the previous
handler in the list, until it eventually propogates to the wire.

Saturnine provides a number of simple Handlers by default, which are represented
in the handler lsit by keys.  For example, here is a simple REPL server implemented
with only the built-in handlers:

    (use 'saturnine.core)

    (start-server 1234 :nonblocking :string [:print "repl"] :clj :echo)

This starts a nonblocking server listening on port 1234.  Once a connection is opened to
this server, incoming data is processed in the following manner:


    |--> :nonblocking --> :string --> :print --> :clj --|
    |                                                   v

    network                                             :echo

    ^                                                   |
    |--  :nonblocking <-- :string <-- :print <-- :clj <--


1. :string - Incoming data is converting to string data and flushed to the next
   handler when a newline is encountered.

2. :print - Incoming strings are logged via clojure.contrib.logging, then
   flushed to the next handler.  Log lines qualified by "repl"

3. :clj - Incoming strings are processed by the Clojure's read-string  and
   eval'd, then the resulting forms are flushed to the next handler

4. :echo - echo's every incoming Clojure form, back down the stack in reverse.

5. :clj - the now-outbound Clojure form is flushed as a string with print-str

6. :print - outbound strings are logged via clojure.contrib.logging, then
   flushed

7. :string - converts incoming Strings back into Bytes to send to the Connection


Eventually, you'll want to write your own handlers to process data - Saturnine provides
the defhandler macro for this purpose.  Think of defhandler as a souped-up defrecord,
specifically for writing Handlers - functions from the Handler protocl that are not provided
as arguments are replaced with default implementations that should do abotu what you'd
expect.  For example, here's an application with a custom handler which only
responds to "upstream" messages, usign the default implementations for other event
types:

    (use 'saturnine.core 'saturnine.handler)

    (defhandler Sum [sum]
      (upstream [this msg] (let [new-sum (+ sum msg)]
                             (send-down (str "Sum is " new-sum "\r\n"))
                             (assoc this :sum new-sum))))

    (start-server 1234 :blocking :string :clj (new Sum 0))

Breaking this down piece by piece, Sum is a clojure datatype with a single property, :sum,
and implements a single function, upstream.  By declaring sum-server to use the handler
(Sum 0), we are telling Saturnine to assign new Connections this value.  When
an upstream message is passed upstream from :clj, the upstream function on Sum is
called;  this function dispatches a new downstream message with the send-down
function, then returns a new Sum, which becomes the new state of the connection,
and will be called on all future messages from this connection that are flushed
from :clj.

Here's an entire telnet chat server, which uses a Clojure Ref as it's state, allowing
indepedent connections to communicate with eachother simply and thread-safely:

    (use 'saturnine.core 'saturnine.handler)

    (defn- write-all
      [users msg]
      (doseq [user (vals (dissoc users (get-ip)))]
        (write user (str (get-ip) " : " msg))))

    (defhandler Chat [users]
      (connect    [_] (do (dosync (alter users assoc (get-ip) (get-connection)))
                          (write-all @users "User connected!\r\n")))
      (disconnect [_] (do (dosync (alter users dissoc (get-ip)))
                          (write-all @users "User disconnected!\r\n")))
      (upstream   [_ msg] (do (write-all @users msg))))

    (start-server 3333 :string [:print "chat"] (new Chat (ref {})))

For further examples, please see the [API Documentation](http://texodus.github.com/saturnine) and
['saturnine.examples](http://github.com/texodus/saturnine/tree/master/src/saturnine/examples.clj) namespace.





### Planned Features (global backlog) ###

- UDP support
- Filesystem support
- Sequential flow control combinators for simple "conversational" control flows
  in a single handler. (maybe a special sequential handler?)
- XMPP enchancement, maybe some bosh?
- Comet-made-easy, websockets-made-easy
- Take the java out of SSL
- :pre checks for state type verification
- Optimization
    - Implement stateless variation on handlers
    - Replace SimpleChannelHandler with reified ChannelHandler
    - Add support for zero-copy ChannelBuffer manipulation (including file serving)
- Additional Handler optional message endpoints - bind, open, etc.
- Add callbacks to send-up/send-down that are actually useful
- Better HTTP support in the form of a ring adapter.
- Open to suggestions ....



