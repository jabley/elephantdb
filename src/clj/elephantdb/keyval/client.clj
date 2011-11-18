(ns elephantdb.keyval.client
  (:use elephantdb.keyval.config
        elephantdb.keyval.thrift
        elephantdb.common.hadoop
        elephantdb.common.util
        [elephantdb.common.types :only (serialize)]
        hadoop-util.core)
  (:require [elephantdb.common.shard :as shard]
            [elephantdb.common.config :as conf]
            [elephantdb.common.log :as log])
  (:import [elephantdb.generated ElephantDB$Iface WrongHostException
            DomainNotFoundException DomainNotLoadedException]
           [org.apache.thrift TException])
  (:gen-class :init init
              :implements [elephantdb.iface.IElephantClient]
              :constructors {[java.util.Map String] []
                             [java.util.Map
                              java.util.Map
                              elephantdb.generated.ElephantDB$Iface] []}
              :state state))

(def example-shard-index
  {"index-a" {::hosts-to-shards {"host1" #{1, 3}, "host2" #{2, 4}}
              ::shards-to-hosts {1 #{"host1"}, 2 #{"host2"},
                                 3 #{"host1"}, 4 #{"host2"}}}})

(defn -init
  "fs-conf is a map meant to create a hadoop Filesystem object. For
   AWS, this usually includes `fs.default.name`,
   `fs.s3n.awsAccessKeyId` and `fs.s3n.awsSecretAccesskey`.

   global-conf-path: path to global-conf.clj on the filesystem
   specified by fs-conf.

   local-elephant: instance returned by elephantdb.keyval/edb-proxy."
  ([fs-conf global-conf-path]
     (-init fs-conf
            (conf/read-clj-config (filesystem fs-conf)
                                  global-conf-path)
            nil))
  ([fs-conf global-conf local-elephant]
     (let [{:keys [domains hosts replication]} global-conf]
       [[] {:local-hostname       (local-hostname)
            :local-elephant       local-elephant
            :global-conf          global-conf
            :domain-shard-indexes (shard/shard-domains (filesystem fs-conf)
                                                       domains
                                                       hosts
                                                       replication)}])))

(defn- get-index
  "Returns map of domain->host-sets and host->domain-sets."
  [this domain]
  (if-let [index (-> (.state this)
                     (:domain-shard-indexes)
                     (get domain))]
    index
    (throw (domain-not-found-ex domain))))

(defn- my-local-elephant
  "Returns a reference to the local elephantdb service."
  [this]
  (-> this .state :local-elephant))

(defn- my-local-hostname
  "Returns a reference to the client's local hostname."
  [this]
  (-> this .state :local-hostname))

{:elephantdb.common.shard/domain->host {"host1" #{1, 3}, "host2" #{2, 4}}
 :elephantdb.common.shardhost->domain {1 #{"host1"}, 2 #{"host2"},
                                       3 #{"host1"}, 4 #{"host2"}}}

(defn- get-priority-hosts [this domain key]
  (let [hosts (shuffle (shard/key-hosts domain
                                        (get-index this domain)
                                        key))
        localhost (my-local-hostname this)]
    (if (some #{localhost} hosts)
      (cons localhost (remove-val localhost hosts))
      hosts)))

(defn- ring-port [this]
  (-> this .state :global-conf :port))

(defn multi-get-remote
  {:dynamic true}
  [host port domain keys]
  (with-elephant-connection host port client
    (.directMultiGet client domain keys)))

;; If the client has a "local-elephant", or an enclosed edb service,
;; and the "totry" private IP address fits the local-hostname, go
;; ahead and do a direct multi-get internally. Else, create a
;; connection to the other server and do that direct multiget.
(defn- try-multi-get [this domain keys totry]
  (let [suffix (format "%s:%s/%s" totry domain keys)]
    (try (if (and (my-local-elephant this)
                  (= totry (my-local-hostname this)))
           (.directMultiGet (my-local-elephant this) domain keys)
           (multi-get-remote totry (ring-port this) domain keys))
         (catch TException e
           ;; try next host
           (log/error e "Thrift exception on " suffix))
         (catch WrongHostException e
           (log/error e "Fatal exception on " suffix)
           (throw (TException. "Fatal exception when performing get" e)))
         (catch DomainNotFoundException e
           (log/error e "Could not find domain when executing read on " suffix)
           (throw e))
         (catch DomainNotLoadedException e
           (log/error e "Domain not loaded when executing read on " suffix)
           (throw e)))))

(defn -get [this domain key]
  (first (.multiGet this domain [key])))

(defn -getInt [this domain ^Integer key]
  (.get this domain (serialize key)))

(defn -getLong [this domain ^Long key]
  (.get this domain (serialize key)))

(defn -getString [this domain ^String key]
  (.get this domain (serialize key)))

(defn- host-indexed-keys
  "returns [hosts-to-try global-index key all-hosts] seq"
  [this domain keys]
  (for [[gi key] (map-indexed vector keys)
        :let [priority-hosts (get-priority-hosts this domain key)]]
    [priority-hosts gi key priority-hosts]))

(defn- multi-get*
  "executes multi-get, returns seq of [global-index val]"
  [this domain host host-indexed-keys key-shard-fn multi-get-remote-fn]
  (binding [shard/key-shard  key-shard-fn
            multi-get-remote multi-get-remote-fn]
    (when-let [vals (try-multi-get this domain (map third host-indexed-keys) host)]
      (map (fn [v [hosts gi key all-hosts]] [gi v])
           vals
           host-indexed-keys))))


(defn -multiGet
  "This is really the only function that matters for ElephantDB
  Key-Value; all others are just views on this. And THIS only depends
  on directMultiGet.

  directMulti"
  [this domain keys]
  (let [host-indexed-keys (host-indexed-keys this domain keys)]
    (loop [keys-to-get host-indexed-keys
           results []]
      (let [host-map (group-by ffirst keys-to-get)
            rets (parallel-exec
                  (for [[host host-indexed-keys] host-map]
                    (constantly
                     [host (multi-get* this
                                       domain
                                       host
                                       host-indexed-keys
                                       shard/key-shard
                                       multi-get-remote)])))
            succeeded       (filter second rets)
            succeeded-hosts (map first succeeded)
            results (->> (map second succeeded)
                         (apply concat results))
            failed-host-map (apply dissoc host-map succeeded-hosts)]
        (if (empty? failed-host-map)
          (map second (sort-by first results))
          (recur (for [[[_ & hosts] gi key all-hosts]
                       (apply concat (vals failed-host-map))]
                   (if (empty? hosts)
                     (throw (hosts-down-ex all-hosts))
                     [hosts gi key all-hosts]))
                 results))))))

(defn -multiGetInt [this domain integers]
  (.multiGet this domain (map serialize integers)))

(defn -multiGetLong [this domain longs]
  (.multiGet this domain (map serialize longs)))

(defn -multiGetString [this domain strings]
  (.multiGet this domain (map serialize strings)))


Orders
;; id,acc,user

(def user-src
  [[1 1 1]
   [2 1 1]
   [3 1 1]
   [4 2 1]
   [5 2 1]
   [6 3 2]
   [7 4 2]])

;; id,credit
(def opportunities-src
  [[1 true]
   [2 true]
   [3 true]
   [4 false]])

(defn account-query
  [opp-path accounts-path]
  (let [opptys (hfs-textline opp-path)
        accounts (hfs-textline accounts-path)]
    (?<- (stdout)
         [?user ?count]
         (opptys ?o-line)
         (my-csv-parser ?o-line :> ?oppty-id ?acc-id ?user)
         (accounts ?a-line)
         (my-csv-parser ?a-line :> ?acc-id ?acc-credit)
         (c/count ?count))))
