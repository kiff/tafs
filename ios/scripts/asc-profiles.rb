#!/usr/bin/env ruby
# Makes sure each bundle ID has an App Store profile with our distribution
# certificate, through the App Store Connect API, and installs them for
# xcodebuild. Prints names and ids only, never a key or a profile's content.
#
#   ASC_KEY_ID, ASC_ISSUER_ID, ASC_KEY_PATH  the API key
#   DIST_CERT_SERIAL                          the certificate in the keychain
#   ruby asc-profiles.rb bot.tafs.app+push+timesensitive="tafs App Store CI" bot.tafs.app.widgets="tafs widgets App Store CI"
#
# "+push" turns on Push Notifications for that bundle ID first, and replaces a
# profile made before it was on: a profile carries the capabilities its bundle
# ID had when it was made. "+timesensitive" (kiff/tafs#210) replaces a profile
# made without Time Sensitive Notifications too, but the API can't turn that on:
# it is turned on once for the bundle ID in the developer portal, and until
# then this stops and says so.
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

# Each "+capability": App Store Connect's type (nil: the API has none), and the
# entitlement a profile made with it names.
CAPABILITIES = {
  "push" => ["PUSH_NOTIFICATIONS", "aps-environment"],
  "timesensitive" => [nil, "com.apple.developer.usernotifications.time-sensitive"],
}

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
  unknown = capabilities - CAPABILITIES.keys
  abort "unknown capability in #{spec}" unless unknown.empty?
  wanted = CAPABILITIES.slice(*capabilities)
  bundle = api("get", "/v1/bundleIds?filter[identifier]=#{identifier}&limit=5")["data"].find { |b| b.dig("attributes", "identifier") == identifier }
  # A new extension's bundle ID (the notification extension, kiff/tafs#210) is registered
  # here, under the app's: only ever one below bot.tafs.app.
  if bundle.nil? && identifier.start_with?("bot.tafs.app.")
    bundle = api("post", "/v1/bundleIds", { data: { type: "bundleIds",
      attributes: { identifier: identifier, name: "tafs #{identifier.split(".").last}", platform: "IOS" } } })["data"]
    puts "registered bundle ID #{identifier}"
  end
  abort "bundle ID #{identifier} is not registered" unless bundle

  unless wanted.empty?
    have = api("get", "/v1/bundleIds/#{bundle["id"]}/bundleIdCapabilities")["data"] || []
    wanted.each do |label, (type, _)|
      next if type.nil? || have.any? { |c| c.dig("attributes", "capabilityType") == type }
      api("post", "/v1/bundleIdCapabilities", { data: { type: "bundleIdCapabilities",
        attributes: { capabilityType: type },
        relationships: { bundleId: { data: { type: "bundleIds", id: bundle["id"] } } } } })
      puts "turned on #{label} for #{identifier}"
    end
  end

  found = api("get", "/v1/profiles?filter[name]=#{URI.encode_www_form_component(name)}&include=certificates&limit=20")
  profile = (found["data"] || []).find do |p|
    p.dig("attributes", "profileState") == "ACTIVE" &&
      (p.dig("relationships", "certificates", "data") || []).any? { |c| c["id"] == cert["id"] } &&
      # The profile's signed plist names each capability's entitlement only when it was made with it on.
      wanted.values.all? { |(_, entitlement)| Base64.decode64(p.dig("attributes", "profileContent").to_s).include?(entitlement) }
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
  content = Base64.decode64(profile.dig("attributes", "profileContent").to_s)
  missing = wanted.reject { |_, (_, entitlement)| content.include?(entitlement) }.keys
  unless missing.empty?
    abort "#{identifier}'s new profile lacks #{missing.join(", ")}: turn it on for #{identifier} in the developer portal (Identifiers), then run this again"
  end
  uuid = profile.dig("attributes", "uuid")
  File.binwrite(File.join(dir, "#{uuid}.mobileprovision"), Base64.decode64(profile.dig("attributes", "profileContent")))
  puts "installed #{name} for #{identifier}"
end
