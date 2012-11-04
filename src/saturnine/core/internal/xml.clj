(ns saturnine.core.internal.xml
  
  (:gen-class))




;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;
;;;; Util

(defn- push
  [element]
  {:state    element
   :messages [(into {} element)]}) 

(defn emit [e]
  (if (string? e)
    e
    (str (when (not (= (:tag e) :characters)) "<")
	 (when (= (:tag e) :end-element) "/")
	 (:qname e)
	 (when (:attrs e)
	   (apply str (for [attr (:attrs e)]
			(str " " (name (key attr)) "='" (val attr)"'"))))
	 (when (not (= (:tag e) :characters)) ">")
	 "\r\n")))





;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;
;;;; Character Parser

(defmulti parse (fn [element #^Character x] (:state element)))

(defmethod parse nil                                          ; don't know the current state
  [element c]
  (if (not (or (= c \newline) 
	       (= c \space) 
	       (= c \tab) 
	       (= c (first "\r"))))
    {:state (if (= c \<)
	      (assoc element 
		:state :pre-qname 
		:tag   :start-element 
		:qname nil 
		:attrs [])                                    ; this is an XML element
	      (assoc element  
		:state :characters 
		:tag   :characters 
		:qname c 
		:attrs []))}))                                ; this is character data

(defmethod parse :pre-qname                                   ; element has just started
  [element c]
  {:state (condp = c 
	    \/ (assoc element 
		 :state :qname 
		 :tag   :end-element)                         ; this is an end tag
	    \? (assoc element :state :dtd)
	    (assoc element 
	      :state :qname
	      :qname c
	      :tag   :start-element))})                       ; type element is undetermined

(defmethod parse :dtd
  [element c]
  {:post [(not (nil? %))]}
  {:state (if (= c \>) 
	    (assoc element :state nil)
	    element)})

(defmethod parse :characters                                  ; parsing character data
  [{qname :qname :as element} c]
  (if (= c \<)
    {:state    (assoc element 
		 :qname nil 
		 :state :pre-qname)
     :messages [(assoc element :state :pre-qname)]}           ; character data is over
    {:state (assoc element :qname (str qname c))}))           ; more character data

(defmethod parse :qname                                       ; parsing qname
  [{qname :qname :as element} c]
  (condp = c
    \> (push (assoc element :state nil))                      ; this is the end of a start-element
    \  {:state (assoc element :state :between)}               ; qname is finished, switching to attr-name
    \/ (push (assoc element 
		 :tag   :start-element
		 :state :must-end))	                      ; qname is finished, switching to must-terminate
    {:state (assoc element :qname (str qname c))}))           ; more qname characters

(defmethod parse :between                                     ; between xml terms
  [element c]
  (condp = c
    \/ (push (assoc element 
		 :tag   :start-element
		 :state :must-end))                           ; element is finished, switch to must-terminate
    \  {:state element}                                       ; no-op
    \> (push (assoc element :state nil))                      ; end of element
    {:state (assoc element                                    ; first attr-name character
	      :state :attr-name 
	      :attrs (cons [c ""] (:attrs element)))})) 

(defmethod parse :must-end                                    ; parsed a / but not terminated
  [element c]
  (if (= c \>)
    (push (assoc element 
	      :tag   :end-element 
	      :state nil))                                    ; parsed a / but not terminated
    (push (assoc element 
	      :state nil 
	      :tag   :malformed))))                           ; malformed

(defmethod parse :attr-name                                   ; element is malformed
  [{attrs :attrs :as element} c]
  (condp = c 
    \  nil                                                    ; attr-name is over, no-op
    \= {:state (assoc element :state :pre-attr-value)}        ; attr-name is over, switch to pre-attr-value
    {:state (assoc element                                    ; more attr-name characters
	      :attrs (cons [(str (first (first attrs)) c) ""] 
			   (drop 1 attrs)))}))

(defmethod parse :pre-attr-value                              ; finished parsing an attr-name but not started a value yet
  [element c]
  (condp = c
    \" {:state (assoc element :state :attr-value)}            ; start an attr-value
    \' {:state (assoc element :state :attr-value)}
    (push (assoc element 
	      :state nil 
	      :tag   :malformed))))                           ; malformed

(defmethod parse :attr-value                                  ; parsing attribute value
  [{attrs :attrs :as element} c]
  (condp = c
    \" {:state (assoc element :state :between)}
    \' {:state (assoc element :state :between)}
    {:state (assoc element                                    ; more attr-value characters
	      :attrs (cons [(first (first attrs)) 
			    (str (second (first attrs)) c)]
			   (drop 1 (:attrs element))))}))

  (defmethod parse :default
    [element c]
    (push (assoc element :state nil :tag :malformed)))
