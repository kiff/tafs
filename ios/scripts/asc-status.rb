#!/usr/bin/env ruby
# Says where the App Store stands, read only: for each platform of the app, its newest App Store versions with Apple's
# review state, and the latest TestFlight builds with their processing, internal and external states. It never writes,
# uploads or submits, and prints only versions, build numbers and states: no id, key, token, email or account detail
# (this repository is public, and so are its Actions logs).
#
#   ASC_KEY_ID, ASC_ISSUER_ID, ASC_KEY_PATH  the API key
#   ruby asc-status.rb
#
# The steps are in AscStatus.run, which takes the API as a function, so test_asc_status.rb runs them against canned
# answers.
require "openssl"
require "json"
require "base64"
require "net/http"
require "uri"

module AscStatus
  BUNDLE_ID = "bot.tafs.app"
  PLATFORMS = { "IOS" => "iPhone and iPad", "MAC_OS" => "macOS" }.freeze
  # App Store states in which Apple has asked the developer for something, or has said no.
  ASKS = %w[REJECTED METADATA_REJECTED INVALID_BINARY DEVELOPER_REJECTED].freeze
  # How many versions per platform and how many builds are listed.
  VERSIONS = 2
  BUILDS = 6

  class Failure < StandardError; end

  module_function

  # api.(method, path) → [status, data]; only "get" is ever called.
  def run(api, out: $stdout)
    app = api.("get", "/v1/apps?filter[bundleId]=#{BUNDLE_ID}")[1]["data"].first or raise Failure, "no app #{BUNDLE_ID}"
    app_id = app["id"]
    asked = false

    versions = api.("get", "/v1/apps/#{app_id}/appStoreVersions?limit=30")[1]["data"] || []
    out.puts "App Store"
    PLATFORMS.each do |platform, label|
      mine = versions.select { |v| v.dig("attributes", "platform") == platform }
                     .sort_by { |v| v.dig("attributes", "createdDate").to_s }.reverse.first(VERSIONS)
      if mine.empty?
        out.puts "  #{label}: no version"
        next
      end
      mine.each do |v|
        state = v.dig("attributes", "appStoreState").to_s
        asked ||= ASKS.include?(state)
        out.puts "  #{label} #{v.dig("attributes", "versionString")}: #{state.empty? ? "unknown" : state}"
      end
    end
    out.puts "  Apple has asked for something: #{asked ? "yes (a version was rejected)" : "no"}"

    builds = api.("get", "/v1/builds?filter[app]=#{app_id}&sort=-uploadedDate&limit=#{BUILDS}&include=preReleaseVersion")
    pre = (builds[1]["included"] || []).select { |i| i["type"] == "preReleaseVersions" }.to_h { |i| [i["id"], i["attributes"] || {}] }
    out.puts "TestFlight"
    list = builds[1]["data"] || []
    out.puts "  no builds" if list.empty?
    list.each do |b|
      a = b["attributes"] || {}
      release = pre[b.dig("relationships", "preReleaseVersion", "data", "id")] || {}
      beta = api.("get", "/v1/builds/#{b["id"]}/buildBetaDetail")[1]["data"]
      beta = beta.is_a?(Hash) ? beta["attributes"] || {} : {}
      label = PLATFORMS[release["platform"]] || release["platform"] || "?"
      out.puts "  #{label} #{release["version"]} (build #{a["version"]}): #{a["processingState"]}#{a["expired"] ? ", expired" : ""}, " \
               "internal #{beta["internalBuildState"] || "-"}, external #{beta["externalBuildState"] || "-"}, " \
               "uploaded #{a["uploadedDate"].to_s[0, 10]}"
    end
    out.puts "Read at #{Time.now.utc.strftime("%Y-%m-%d %H:%M")} UTC"
  end

  def errors(body)
    (body["errors"] || []).map { |e| "#{e["code"]}: #{e["detail"]}" }.join("; ")
  end
end

if $PROGRAM_NAME == __FILE__
  jwt = lambda do
    b64 = ->(s) { Base64.urlsafe_encode64(s, padding: false) }
    key = OpenSSL::PKey::EC.new(File.read(ENV.fetch("ASC_KEY_PATH")))
    now = Time.now.to_i
    head = b64.(JSON.generate(alg: "ES256", kid: ENV.fetch("ASC_KEY_ID"), typ: "JWT"))
    body = b64.(JSON.generate(iss: ENV.fetch("ASC_ISSUER_ID"), iat: now, exp: now + 600, aud: "appstoreconnect-v1"))
    asn = OpenSSL::ASN1.decode(key.sign(OpenSSL::Digest::SHA256.new, "#{head}.#{body}"))
    sig = asn.value.map { |i| i.value.to_s(2).rjust(32, "\x00".b)[-32, 32] }.join
    "#{head}.#{body}.#{b64.(sig)}"
  end
  token = jwt.()
  api = lambda do |method, path|
    raise AscStatus::Failure, "only reads" unless method == "get"
    uri = URI("https://api.appstoreconnect.apple.com#{path}")
    req = Net::HTTP::Get.new(uri)
    req["Authorization"] = "Bearer #{token}"
    res = Net::HTTP.start(uri.host, uri.port, use_ssl: true) { |h| h.request(req) }
    data = res.body.to_s.empty? ? {} : JSON.parse(res.body)
    status = res.code.to_i
    # A build with no beta detail answers 404: that's an empty state, not a failure.
    raise AscStatus::Failure, "App Store Connect GET #{uri.path} answered #{status}: #{AscStatus.errors(data)}" if status >= 400 && status != 404
    [status, data]
  end
  begin
    AscStatus.run(api)
  rescue AscStatus::Failure => e
    abort e.message
  end
end
