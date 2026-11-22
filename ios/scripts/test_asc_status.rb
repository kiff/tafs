# Runs asc-status.rb against canned App Store Connect answers.
#   ruby ios/scripts/test_asc_status.rb
require "minitest/autorun"
require "stringio"
require_relative "asc-status"

class AscStatusTest < Minitest::Test
  def api(routes, calls = [])
    lambda do |method, path|
      calls << [method, path]
      routes.fetch(path) { raise "unexpected #{method} #{path}" }
    end
  end

  def routes(rejected: false)
    {
      "/v1/apps?filter[bundleId]=bot.tafs.app" => [200, { "data" => [{ "id" => "SECRET-APP-ID" }] }],
      "/v1/apps/SECRET-APP-ID/appStoreVersions?limit=30" => [200, { "data" => [
        { "attributes" => { "platform" => "IOS", "versionString" => "1.0", "appStoreState" => rejected ? "REJECTED" : "WAITING_FOR_REVIEW", "createdDate" => "2026-11-20T10:00:00Z" } },
        { "attributes" => { "platform" => "MAC_OS", "versionString" => "1.0", "appStoreState" => "IN_REVIEW", "createdDate" => "2026-11-21T10:00:00Z" } },
      ] }],
      "/v1/builds?filter[app]=SECRET-APP-ID&sort=-uploadedDate&limit=6&include=preReleaseVersion" => [200, {
        "data" => [{ "id" => "b-175", "attributes" => { "version" => "175", "processingState" => "VALID", "expired" => false, "uploadedDate" => "2026-11-22T09:00:00Z" },
                     "relationships" => { "preReleaseVersion" => { "data" => { "id" => "p1" } } } }],
        "included" => [{ "type" => "preReleaseVersions", "id" => "p1", "attributes" => { "version" => "1.0", "platform" => "IOS" } }],
      }],
      "/v1/builds/b-175/buildBetaDetail" => [200, { "data" => { "attributes" => { "internalBuildState" => "IN_BETA_TESTING", "externalBuildState" => "READY_FOR_BETA_SUBMISSION" } } }],
    }
  end

  def report(routes, calls = [])
    out = StringIO.new
    AscStatus.run(api(routes, calls), out: out)
    out.string
  end

  def test_it_says_each_platforms_state_and_the_builds
    text = report(routes)
    assert_includes text, "iPhone and iPad 1.0: WAITING_FOR_REVIEW"
    assert_includes text, "macOS 1.0: IN_REVIEW"
    assert_includes text, "Apple has asked for something: no"
    assert_includes text, "iPhone and iPad 1.0 (build 175): VALID, internal IN_BETA_TESTING, external READY_FOR_BETA_SUBMISSION, uploaded 2026-11-22"
  end

  def test_a_rejected_version_says_apple_asked
    assert_includes report(routes(rejected: true)), "Apple has asked for something: yes"
  end

  def test_it_prints_no_id
    text = report(routes)
    refute_match(/SECRET-APP-ID|b-175|\bp1\b/, text)
  end

  def test_it_only_reads
    calls = []
    report(routes, calls)
    assert_equal ["get"], calls.map(&:first).uniq
  end

  def test_a_platform_with_no_version_says_so
    r = routes
    r["/v1/apps/SECRET-APP-ID/appStoreVersions?limit=30"] = [200, { "data" => [] }]
    assert_includes report(r), "macOS: no version"
  end
end
