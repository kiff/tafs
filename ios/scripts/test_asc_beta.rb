# Runs asc-beta.rb's steps against canned App Store Connect answers.
#   ruby ios/scripts/test_asc_beta.rb
require "minitest/autorun"
require "stringio"
require_relative "asc-beta"

class FakeASC
  attr_reader :calls

  def initialize
    @calls = []
    @routes = {
      ["get", "/v1/apps?filter[bundleId]=bot.tafs.app"] => [200, { "data" => [{ "id" => "app1" }] }],
      ["get", "/v1/apps/app1/betaGroups"] => [200, { "data" => [{ "id" => "grp1", "attributes" => { "name" => "Beta testers", "isInternalGroup" => false } }] }],
      ["get", "/v1/apps/app1/betaAppLocalizations"] => [200, { "data" => [{ "id" => "loc1", "attributes" => { "locale" => "en-US" } }] }],
      ["get", "/v1/apps/app1/betaAppReviewDetail"] => [200, { "data" => { "id" => "rev1", "attributes" => { "contactEmail" => "" } } }],
      ["get", "/v1/builds?filter[app]=app1&filter[version]=7"] => [200, { "data" => [{ "id" => "b7", "attributes" => { "processingState" => "VALID" } }] }],
      ["get", "/v1/builds/b7/buildBetaDetail"] => [200, { "data" => { "id" => "bbd7", "attributes" => { "autoNotifyEnabled" => false, "externalBuildState" => "READY_FOR_BETA_SUBMISSION" } } }],
      ["post", "/v1/betaAppReviewSubmissions"] => [201, { "data" => { "id" => "sub1" } }],
      ["get", "/v1/betaTesters?filter[email]=t%40example.com"] => [200, { "data" => [] }],
    }
  end

  def on(method, path, status, body)
    @routes[[method, path]] = [status, body]
  end

  def to_proc
    lambda do |method, path, body = nil, ok = []|
      @calls << [method, path, body]
      status, data = @routes.fetch([method, path]) { [200, { "data" => {} }] }
      raise AscBeta::Failure, "#{method} #{path} answered #{status}" if status >= 400 && !ok.include?(status)
      [status, data]
    end
  end

  def index(method, path)
    @calls.index { |m, p, _| m == method && p == path }
  end
end

class AscBetaTest < Minitest::Test
  ENV_OK = { "BUILD_NUMBER" => "7", "BETA_CONTACT" => "Ada:Lovelace:+100:ada@example.com",
             "BETA_FEEDBACK_EMAIL" => "feedback@example.com", "TESTERS" => "t@example.com:Tess:Ter" }.freeze

  def run_with(asc, env = ENV_OK)
    out = StringIO.new
    AscBeta.run(asc.to_proc, env, out: out, wait: ->(_) {})
    out.string
  end

  def test_adds_the_build_turns_on_notification_then_submits_and_invites
    asc = FakeASC.new
    out = run_with(asc)
    added = asc.index("post", "/v1/betaGroups/grp1/relationships/builds")
    notify = asc.index("patch", "/v1/buildBetaDetails/bbd7")
    submitted = asc.index("post", "/v1/betaAppReviewSubmissions")
    refute_nil added
    refute_nil notify, "an approved build reaches testers without a manual Notify"
    assert_equal({ data: { type: "buildBetaDetails", id: "bbd7", attributes: { autoNotifyEnabled: true } } }, asc.calls[notify][2])
    assert added < notify && notify < submitted, "in the group and notifying before it goes to review"
    refute_nil asc.index("post", "/v1/betaTesters")
    assert_includes out, "submitted to Beta App Review"
    refute_includes out, "example.com", "no email in the log"
  end

  def test_a_build_already_in_review_is_fine
    asc = FakeASC.new
    asc.on("post", "/v1/betaAppReviewSubmissions", 409, { "errors" => [{ "code" => "ENTITY_ERROR", "detail" => "already submitted" }] })
    asc.on("get", "/v1/builds/b7/buildBetaDetail", 200, { "data" => { "id" => "bbd7", "attributes" => { "autoNotifyEnabled" => true, "externalBuildState" => "WAITING_FOR_BETA_REVIEW" } } })
    out = run_with(asc)
    assert_includes out, "already submitted (WAITING_FOR_BETA_REVIEW)"
    assert_nil asc.index("patch", "/v1/buildBetaDetails/bbd7"), "notification already on"
  end

  def test_a_refused_submission_fails_with_the_reason
    asc = FakeASC.new
    asc.on("post", "/v1/betaAppReviewSubmissions", 422,
           { "errors" => [{ "code" => "ENTITY_ERROR", "detail" => "Another build is in review for this version" }] })
    err = assert_raises(AscBeta::Failure) { run_with(asc) }
    assert_includes err.message, "wasn't submitted to Beta App Review (it is READY_FOR_BETA_SUBMISSION)"
    assert_includes err.message, "Another build is in review"
    assert_nil asc.index("post", "/v1/betaTesters"), "nothing after a failed submission"
  end

  def test_needs_a_review_contact
    asc = FakeASC.new
    err = assert_raises(AscBeta::Failure) { run_with(asc, ENV_OK.merge("BETA_CONTACT" => "")) }
    assert_includes err.message, "BETA_CONTACT"
  end

  def test_waits_for_processing
    asc = FakeASC.new
    seen = 0
    real = asc.to_proc
    api = lambda do |method, path, body = nil, ok = []|
      if path == "/v1/builds?filter[app]=app1&filter[version]=7" && (seen += 1) < 3
        next [200, { "data" => [{ "id" => "b7", "attributes" => { "processingState" => "PROCESSING" } }] }]
      end
      real.(method, path, body, ok)
    end
    waited = 0
    AscBeta.run(api, ENV_OK, out: StringIO.new, wait: ->(_) { waited += 1 })
    assert_equal 2, waited
  end
end
