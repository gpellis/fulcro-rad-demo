(ns com.example.model.authorization
  (:require
    [com.fulcrologic.fulcro.server.api-middleware :as fmw]
    [com.fulcrologic.rad.authorization :as auth]
    [com.fulcrologic.rad.database-adapters.datomic :as datomic]
    [com.fulcrologic.rad.attributes :as attr]
    [datomic.api :as d]
    [taoensso.encore :as enc]
    [taoensso.timbre :as log]
    [com.example.model.timezone :as timezone]))

(def zone-key->zone-id
  (timezone/namespaced-time-zone-ids "account.time-zone"))

(defn login!
  "Implementation of login. This is database-specific and is not further generalized for the demo."
  [{::datomic/keys [databases] :as env} {:keys [username password]}]
  (log/info "Attempt login for" username)
  (enc/if-let [db                   @(:production databases)
               {:account/keys  [name time-zone]
                :password/keys [hashed-value salt iterations]} (d/pull db [:account/name
                                                                           {:account/time-zone [:db/ident]}
                                                                           :password/hashed-value
                                                                           :password/salt
                                                                           :password/iterations]
                                                                 [:account/email username])
               current-hashed-value (attr/encrypt password salt iterations)]
    (if (= hashed-value current-hashed-value)
      (do
        (log/info "Login for" username)
        (let [s {::auth/provider    :local
                 ::auth/status      :success
                 :account/time-zone (some-> time-zone :db/ident zone-key->zone-id)
                 :account/name      name}]
          (fmw/augment-response s (fn [resp]
                                    (let [current-session (-> env :ring/request :session)]
                                      (assoc resp :session (merge current-session s)))))))
      (do
        (log/error "Login failure for" username)
        {::auth/provider :local
         ::auth/status   :failed}))
    (do
      (log/fatal "Login cannot find user" username)
      {::auth/provider :local
       ::auth/status   :failed})))

(defn check-session! [env]
  (log/info "Checking for existing session")
  (or
    (some-> env :ring/request :session)
    {::auth/provider :local
     ::auth/status   :not-logged-in}))

