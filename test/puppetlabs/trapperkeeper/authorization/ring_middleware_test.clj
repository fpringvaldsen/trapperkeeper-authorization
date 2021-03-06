(ns puppetlabs.trapperkeeper.authorization.ring-middleware-test
  (:require [clojure.test :refer :all]
            [puppetlabs.trapperkeeper.authorization.ring-middleware :as ring-middleware]
            [puppetlabs.trapperkeeper.authorization.rules :as rules]
            [puppetlabs.trapperkeeper.authorization.testutils :as testutils]
            [schema.test :as schema-test]
            [puppetlabs.trapperkeeper.testutils.logging :as logutils]))

(use-fixtures :once schema-test/validate-schemas)

(def test-rule [(-> (testutils/new-rule :path "/path/to/foo")
                    (rules/deny "bad.guy.com")
                    (rules/allow-ip "192.168.0.0/24")
                    (rules/allow "*.domain.org")
                    (rules/allow "*.test.com")
                    (rules/deny-ip "192.168.1.0/24"))])

(deftest ring-request-to-name-test
  (testing "request to name"
    (is (= "test.domain.org"
           (ring-middleware/request->name
            (testutils/request "/path/to/resource" :get "127.0.0.1" testutils/test-domain-cert))))))

(def base-handler
  (fn [request]
    {:status 200 :body "hello"}))

(defn build-ring-handler
  [rules]
  (-> base-handler
      (ring-middleware/wrap-authorization-check rules)))

(deftest wrap-authorization-check-test
  (logutils/with-test-logging
    (let [ring-handler (build-ring-handler test-rule)]
      (testing "access allowed when cert CN is allowed"
        (let [response (ring-handler (testutils/request "/path/to/foo" :get "127.0.0.1" testutils/test-domain-cert))]
          (is (= 200 (:status response)))
          (is (= "hello" (:body response)))))
      (testing "access denied when cert CN is not in the rule"
        (let [response (ring-handler (testutils/request "/path/to/foo" :get "127.0.0.1" testutils/test-other-cert))]
          (is (= 403 (:status response)))
          (is (= (str "Forbidden request: www.other.org(127.0.0.1) access to "
                      "/path/to/foo (method :get) (authentic: true) denied by "
                      "rule 'test rule'.")
                 (:body response)))))
      (testing "access denied when cert CN is explicitly denied in the rule"
        (let [response (ring-handler (testutils/request "/path/to/foo" :get "127.0.0.1" testutils/test-denied-cert))]
          (is (= 403 (:status response)))
          (is (= (str "Forbidden request: bad.guy.com(127.0.0.1) access to "
                      "/path/to/foo (method :get) (authentic: true) denied by "
                      "rule 'test rule'.")
                 (:body response))))))
    (testing "Denied when deny all"
      (let [app (build-ring-handler [(-> (testutils/new-rule :path "/")
                                         (rules/deny "*"))])]
        (doseq [path ["a" "/" "/hip/hop/" "/a/hippie/to/the/hippi-dee/beat"]]
          (let [req (testutils/request path :get "127.0.0.1" testutils/test-domain-cert)
                {status :status} (app req)]
            (is (= status 403))))))))
