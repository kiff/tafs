#!/usr/bin/env ruby
# Makes sure each bundle ID has an App Store profile with our distribution
# certificate, through the App Store Connect API, and installs them for
# xcodebuild. Prints names and ids only, never a key or a profile's content.
#
#   ASC_KEY_ID, ASC_ISSUER_ID, ASC_KEY_PATH  the API key
#   DIST_CERT_SERIAL                          the certificate in the keychain
#   ruby asc-profiles.rb bot.tafs.app+push="tafs App Store CI" bot.tafs.app.widgets="tafs widgets App Store CI"
#
# "+push" turns on Push Notifications for that bundle ID first, and replaces a
# profile made before it was on: a profile carries the capabilities its bundle
# ID had when it was made.
require "openssl"
require "json"
require "base64"
require "net/http"
require "fileutils"

def jwt
  b64 = ->(s) { Base64.urlsafe_encode64(s, padding: false) }
  key = OpenSSL::PKey::EC.new(File.read(ENV.fetch("ASC_KEY_PATH")))
  now = Time.now.to_i
  head = b64.(JSON.generate(alg: "ES256", kid: ENV.fetch("ASC_KEY_ID"), typ: "JWT"))
  body = b64.(JSON.generate(iss: ENV.fetch("ASC_ISSUER_ID"), iat: now, exp: now + 900, aud: "appstoreconnect-v1"))
  asn = OpenSSL::ASN1.decode(key.sign(OpenSSL::Digest::SHA256.new, "#{head}.#{body}"))
  sig = asn.value.map { |i| i.value.to_s(2).rjust(32, "\x00".b)[-32..] }.join
  "#{head}.#{body}.#{b64.(sig)}"
end

TOKEN = jwt

def api(method, path, body = nil)
  uri = URI("https://api.appstoreconnect.apple.com#{path}")
  req = Net::HTTP.const_get(method.capitalize).new(uri)
  req["Authorization"] = "Bearer #{TOKEN}"
  if body
    req["Content-Type"] = "application/json"
    req.body = JSON.generate(body)
  end
  res = Net::HTTP.start(uri.host, uri.port, use_ssl: true) { |h| h.request(req) }
  data = res.body.to_s.empty? ? {} : JSON.parse(res.body)
  unless res.code.start_with?("2")
    errs = (data["errors"] || []).map { |e| "#{e["code"]}: #{e["detail"]}" }.join("; ")
    abort "App Store Connect #{method} #{uri.path} answered #{res.code}: #{errs}"
  end
  data
end

serial = ENV.fetch("DIST_CERT_SERIAL").downcase.sub(/\A0+/, "")
certs = api("get", "/v1/certificates?filter[certificateType]=DISTRIBUTION&limit=200")["data"]
cert = certs.find { |c| c.dig("attributes", "serialNumber").to_s.downcase.sub(/\A0+/, "") == serial }
abort "no distribution certificate with the keychain's serial number" unless cert
puts "certificate #{cert["id"]} (#{cert.dig("attributes", "name")})"

dir = File.expand_path("~/Library/MobileDevice/Provisioning Profiles")
FileUtils.mkdir_p(dir)

ARGV.each do |arg|
  spec, name = arg.split("=", 2)
  identifier, *capabilities = spec.split("+")
  push = capabilities.delete("push")
  abort "unknown capability in #{spec}" unless capabilities.empty?
  bundle = api("get", "/v1/bundleIds?filter[identifier]=#{identifier}&limit=5")["data"].find { |b| b.dig("attributes", "identifier") == identifier }
  abort "bundle ID #{identifier} is not registered" unless bundle

  if push
    have = api("get", "/v1/bundleIds/#{bundle["id"]}/bundleIdCapabilities")["data"] || []
    unless have.any? { |c| c.dig("attributes", "capabilityType") == "PUSH_NOTIFICATIONS" }
      api("post", "/v1/bundleIdCapabilities", { data: { type: "bundleIdCapabilities",
        attributes: { capabilityType: "PUSH_NOTIFICATIONS" },
        relationships: { bundleId: { data: { type: "bundleIds", id: bundle["id"] } } } } })
      puts "turned on Push Notifications for #{identifier}"
    end
  end

  found = api("get", "/v1/profiles?filter[name]=#{URI.encode_www_form_component(name)}&include=certificates&limit=20")
  profile = (found["data"] || []).find do |p|
    p.dig("attributes", "profileState") == "ACTIVE" &&
      (p.dig("relationships", "certificates", "data") || []).any? { |c| c["id"] == cert["id"] } &&
      # The profile's signed plist names aps-environment only when it was made with push on.
      (!push || Base64.decode64(p.dig("attributes", "profileContent").to_s).include?("aps-environment"))
  end
  # A profile of ours that is no longer usable (expired, or for another certificate) is replaced.
  (found["data"] || []).reject { |p| p == profile }.each do |p|
    api("delete", "/v1/profiles/#{p["id"]}")
    puts "removed stale profile #{p["id"]} (#{name})"
  end
  unless profile
    profile = api("post", "/v1/profiles", { data: { type: "profiles",
      attributes: { name: name, profileType: "IOS_APP_STORE" },
      relationships: { bundleId: { data: { type: "bundleIds", id: bundle["id"] } },
                       certificates: { data: [{ type: "certificates", id: cert["id"] }] } } } })["data"]
    puts "created profile #{profile["id"]} (#{name})"
  end
  uuid = profile.dig("attributes", "uuid")
  File.binwrite(File.join(dir, "#{uuid}.mobileprovision"), Base64.decode64(profile.dig("attributes", "profileContent")))
  puts "installed #{name} for #{identifier}"
end
