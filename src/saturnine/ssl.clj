(ns saturnine.ssl
  (:import [java.security KeyStore Security]
           [java.security.cert X509Certificate]
           [javax.net.ssl SSLContext KeyManagerFactory TrustManager SSLEngine]))

(defn #^SSLContext get-ssl-context
  [path key-pass cert-pass]
  (doto (SSLContext/getInstance "TLS")
    (.init (.getKeyManagers (doto (KeyManagerFactory/getInstance "SunX509")
                              (.init (doto (KeyStore/getInstance "JKS")
                                       (.load (ClassLoader/getSystemResourceAsStream path) 
                                              (.toCharArray key-pass)))
                                     (.toCharArray cert-pass))))
           (into-array [(proxy [TrustManager] []
                          (getAcceptedIssuers [] (make-array X509Certificate 0))
                          (checkClientTrusted [x y] nil)
                          (checkServerTrusted [x y] nil))])
           nil)))

(defn get-ssl-engine 
  [#^SSLContext ssl-context #^boolean client-mode]
  (doto (.createSSLEngine ssl-context)
    (.setUseClientMode client-mode)))