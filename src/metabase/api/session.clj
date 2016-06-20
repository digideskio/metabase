(ns metabase.api.session
  "/api/session endpoints"
  (:require [clojure.tools.logging :as log]
            [cemerick.friend.credentials :as creds]
            [cheshire.core :as json]
            [clj-http.client :as http]
            [compojure.core :refer [defroutes GET POST DELETE]]
            [throttle.core :as throttle]
            [metabase.api.common :refer :all]
            [metabase.db :as db]
            [metabase.email.messages :as email]
            [metabase.events :as events]
            (metabase.models [user :refer [User set-user-password! set-user-password-reset-token!]]
                             [session :refer [Session]]
                             [setting :refer [defsetting], :as setting])
            [metabase.util :as u]
            [metabase.util.password :as pass]))


(defn- create-session!
  "Generate a new `Session` for a given `User`. Returns the newly generated session ID."
  [user]
  {:pre  [(map? user) (integer? (:id user)) (:last_login user)]
   :post [(string? %)]}
  (u/prog1 (str (java.util.UUID/randomUUID))
    (db/insert! Session
      :id      <>
      :user_id (:id user))
    (events/publish-event :user-login {:user_id (:id user), :session_id <>, :first_login (not (boolean (:last_login user)))})))


;;; ## API Endpoints

(def ^:private login-throttlers
  {:email      (throttle/make-throttler :email)
   :ip-address (throttle/make-throttler :email, :attempts-threshold 50)}) ; IP Address doesn't have an actual UI field so just show error by email

(defendpoint POST "/"
  "Login."
  [:as {{:keys [email password]} :body, remote-address :remote-addr}]
  {email    [Required Email]
   password [Required NonEmptyString]}
  (throttle/check (login-throttlers :ip-address) remote-address)
  (throttle/check (login-throttlers :email)      email)
  (let [user (db/select-one [User :id :password_salt :password :last_login], :email email, :is_active true)]
    ;; Don't leak whether the account doesn't exist or the password was incorrect
    (when-not (and user
                   (pass/verify-password password (:password_salt user) (:password user)))
      (throw (ex-info "Password did not match stored password." {:status-code 400
                                                                 :errors      {:password "did not match stored password"}})))
    {:id (create-session! user)}))


;; TODO - Where should this go?
(defsetting google-auth-client-id
  "Client ID for Google Auth SSO.") ; TODO - better description

(defsetting google-auth-auto-create-accounts-domain
  "When set, allow users to sign up on their own if their Google account email address is from this domain.")

(defn- google-auth-token-info [^String token]
  (let [{:keys [status body]} (http/post (str "https://www.googleapis.com/oauth2/v3/tokeninfo?id_token=" token))]
    (when-not (= status 200)
      (throw (ex-info "Invalid Google Auth token." {:status-code 400})))
    (u/prog1 (json/parse-string body keyword)
      (when-not (= (:email_verified <>) "true")
        (throw (ex-info "Email is not verified." {:status-code 400}))))))

(defn- email->domain ^String [email]
  (last (re-find #"^.*@(.*$)" email)))

(defn- email-in-domain? [email domain]
  {:pre [(u/is-email? email)]}
  (= (email->domain email) domain))

(defn- autocreate-user-allowed-for-email? [email]
  (when-let [domain (google-auth-auto-create-accounts-domain)]
    (email-in-domain? email domain)))

(defn- check-autocreate-user-allowed-for-email [email]
  (when-not (autocreate-user-allowed-for-email? email)
    ;; Use some wacky status code (428 - Precondition Required) so we will know when to so the error screen specific to this situation
    (throw (ex-info "You'll need an administrator to create a Metabase account before you can use Google to log in."
                    {:status-code 428}))))

(defn- google-auth-create-new-user! [first-name last-name email]
  (check-autocreate-user-allowed-for-email email)
  ;; TODO - actually create new user
  (db/select-one [User :id :last_login] :email "cam@metabase.com"))

(defendpoint POST "/google_auth"
  "Login with Google Auth."
  [:as {{:keys [token]} :body, remote-address :remote-addr}]
  {token [Required NonEmptyString]}
  (throttle/check (login-throttlers :ip-address) remote-address) ; TODO - Should we throttle this? It might keep us from being slammed with calls that have to make outbound HTTP requests, etc.
  ;; Verify the token is valid with Google
  (let [{:keys [given_name family_name email]} (google-auth-token-info token)]
    (log/info "Successfully authenicated Google Auth token for:" given_name family_name)
    (if-let [user (or (db/select-one [User :id :last_login] :email email)
                      (google-auth-create-new-user! given_name family_name email))]
      {:id (create-session! user)})))


(defendpoint DELETE "/"
  "Logout."
  [session_id]
  {session_id [Required NonEmptyString]}
  (check-exists? Session session_id)
  (db/cascade-delete! Session, :id session_id))

;; Reset tokens:
;; We need some way to match a plaintext token with the a user since the token stored in the DB is hashed.
;; So we'll make the plaintext token in the format USER-ID_RANDOM-UUID, e.g. "100_8a266560-e3a8-4dc1-9cd1-b4471dcd56d7", before hashing it.
;; "Leaking" the ID this way is ok because the plaintext token is only sent in the password reset email to the user in question.
;;
;; There's also no need to salt the token because it's already random <3

(def ^:private forgot-password-throttlers
  {:email      (throttle/make-throttler :email)
   :ip-address (throttle/make-throttler :email, :attempts-threshold 50)})

(defendpoint POST "/forgot_password"
  "Send a reset email when user has forgotten their password."
  [:as {:keys [server-name] {:keys [email]} :body, remote-address :remote-addr, :as request}]
  {email [Required Email]}
  (throttle/check (forgot-password-throttlers :ip-address) remote-address)
  (throttle/check (forgot-password-throttlers :email)      email)
  ;; Don't leak whether the account doesn't exist, just pretend everything is ok
  (when-let [user-id (db/select-one-id User, :email email)]
    (let [reset-token        (set-user-password-reset-token! user-id)
          password-reset-url (str (@(ns-resolve 'metabase.core 'site-url) request) "/auth/reset_password/" reset-token)]
      (email/send-password-reset-email email server-name password-reset-url)
      (log/info password-reset-url))))


(def ^:private ^:const reset-token-ttl-ms
  "Number of milliseconds a password reset is considered valid."
  (* 48 60 60 1000)) ; token considered valid for 48 hours

(defn- valid-reset-token->user
  "Check if a password reset token is valid. If so, return the `User` ID it corresponds to."
  [^String token]
  (when-let [[_ user-id] (re-matches #"(^\d+)_.+$" token)]
    (let [user-id (Integer/parseInt user-id)]
      (when-let [{:keys [reset_token reset_triggered], :as user} (db/select-one [User :id :last_login :reset_triggered :reset_token], :id user-id)]
        ;; Make sure the plaintext token matches up with the hashed one for this user
        (when (u/ignore-exceptions
                (creds/bcrypt-verify token reset_token))
          ;; check that the reset was triggered within the last 48 HOURS, after that the token is considered expired
          (let [token-age (- (System/currentTimeMillis) reset_triggered)]
            (when (< token-age reset-token-ttl-ms)
              user)))))))

(defendpoint POST "/reset_password"
  "Reset password with a reset token."
  [:as {{:keys [token password]} :body}]
  {token    Required
   password [Required ComplexPassword]}
  (or (when-let [{user-id :id, :as user} (valid-reset-token->user token)]
        (set-user-password! user-id password)
        ;; after a successful password update go ahead and offer the client a new session that they can use
        {:success    true
         :session_id (create-session! user)})
      (throw (invalid-param-exception :password "Invalid reset token"))))


(defendpoint GET "/password_reset_token_valid"
  "Check is a password reset token is valid and isn't expired."
  [token]
  {token Required}
  {:valid (boolean (valid-reset-token->user token))})


(defendpoint GET "/properties"
  "Get all global properties and their values. These are the specific `Settings` which are meant to be public."
  []
  (setting/public-settings))


(define-routes)
