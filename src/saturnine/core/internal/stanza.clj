(ns saturnine.core.internal.stanza
  (:gen-class)
  (:use [clojure.contrib.logging :only [log]]))





;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;
;;;; Common

(defn- push-content
  [element c]
  (assoc element :content (conj (or (:content element) []) c)))

(defn- push-chars
  [stanza]
  (when (and (= (:state stanza) :chars)
             (some (complement #(. Character (isWhitespace %))) (str (:sb stanza))))
    (push-content (:current stanza) (str (:sb stanza)))))

(defn emit [element]
  (if (string? element)
    [element]
    (if (= (element :tag) :stream:stream)
      [{:tag :start-element, :qname "stream:stream", :attrs {}}]
      (let [tag (apply str (drop 1 (str (:tag element))))]
	(concat [{:tag :start-element, :qname tag, :attrs (:attrs element)}]
		(apply concat (map emit (:content element)))
		[{:tag :end-element, :qname tag}])))))





;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;
;;;; XML parser


(defmulti parse (fn [stanza e] (:tag e)))

(defmethod parse :start-element
  [stanza el]
  (let [e {:tag     (keyword (str (:qname el)))
           :attrs   (into {} (map #(vector (keyword (first %)) (second %)) (:attrs el)))
           :content []}]
    (if (= (:tag e) :stream:stream)
      (if (= 0 (:depth stanza))
	{:state stanza :messages [e]} ; this is a stream, pass and don't acc
	(throw (new Exception "Bad stream format")))
      (do (push-chars stanza)
	  {:state (assoc stanza
		    :depth   (inc (:depth stanza))
		    :stack   (conj (:stack stanza) (:current stanza))
		    :current e
		    :state   :element)}))))

(defmethod parse :end-element
  [stanza el]
  (condp = (:depth stanza)
    0 (log :warn "Stream closed unexpectedly!") ; TODO throw an exception here?
    1 (let [result [((:content (push-content (peek (:stack stanza)) (:current stanza))) 0)]]
	(push-chars stanza)
	{:state (assoc stanza
		  :stack   nil
		  :current {:tag :none, :attrs {}, :content []}
		  :state   :between
		  :sb      nil
		  :depth   0)
	 :messages result})
    (do (push-chars stanza)
	{:state (assoc stanza
		  :depth   (dec (:depth stanza))
		  :current (push-content (peek (:stack stanza)) (:current stanza))
		  :stack   (pop (:stack stanza))
		  :state   :between)})))

(defmethod parse :characters
  [stanza el]
  (do  (let [#^StringBuilder sb (:sb stanza)]
	(. sb (append (:qname el)))
	{:state (assoc stanza
		  :sb (when-not (= (:state stanza) :chars)
			(new StringBuilder)
			(:sb stanza))
		  :state :chars)})))
