#!/usr/bin/env ruby
# Gives a TestFlight build to the external group "Beta testers" through the App
# Store Connect API, and invites testers to it. Internal testers ("Team") get
# every build by themselves; an external group gets only the builds added to
# it, and Apple reviews a build before external testers can install it (Beta
# App Review). Prints no email, key or token.
#
#   ASC_KEY_ID, ASC_ISSUER_ID, ASC_KEY_PATH  the API key
#   BUILD_NUMBER                              the build just uploaded
#   TESTERS                                   "email[:First:Last], …" to invite (secret BETA_TESTERS)
#   BETA_CONTACT                              "First:Last:phone:email", for Beta App Review (secret)
#   BETA_FEEDBACK_EMAIL                       where testers' feedback goes (secret)
# This repository is public: no email or account detail is written here.
#   ruby asc-beta.rb
require "openssl"
require "json"
require "base64"
require "net/http"

BUNDLE_ID = "bot.tafs.app"
GROUP = "Beta testers"
DESCRIPTION = <<~TEXT.strip
  tafs gives a business AI agents, called tafs, that each do one job inside the limits of a Card. KIFF checks every action a taf takes against its Card; what falls outside waits for the owner's answer on this phone, signed with Face ID.

  What to try: start a taf, look at what it did, approve or refuse what it asks for, and turn on alerts so a held call reaches you with the app closed.
TEXT
REVIEW_NOTES = <<~TEXT.strip
  No account is needed to review the app: on the first screen, tap "Try it with sample data". The sample tafs, their Cards and their held calls are on the phone; approve or refuse a held call to see it work. "Sign in with KIFF" connects a real KIFF account (kiff.dev) and isn't needed for review.
TEXT

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

# A token lasts 15 minutes; waiting for a build to process can take longer.
def token
  @token = nil if @token_at && Time.now - @token_at > 600
  @token_at = Time.now if @token.nil?
  @token ||= jwt
end

# Answers [status, body]. Refused (4xx/5xx) aborts unless the status is in `ok`.
def api(method, path, body = nil, ok: [])
  uri = URI("https://api.appstoreconnect.apple.com#{path}")
  req = Net::HTTP.const_get(method.capitalize).new(uri)
  req["Authorization"] = "Bearer #{token}"
  if body
    req["Content-Type"] = "application/json"
    req.body = JSON.generate(body)
  end
  res = Net::HTTP.start(uri.host, uri.port, use_ssl: true) { |h| h.request(req) }
  data = res.body.to_s.empty? ? {} : JSON.parse(res.body)
  status = res.code.to_i
  if status >= 400 && !ok.include?(status)
    errs = (data["errors"] || []).map { |e| "#{e["code"]}: #{e["detail"]}" }.join("; ")
    abort "App Store Connect #{method} #{uri.path} answered #{status}: #{errs}"
  end
  [status, data]
end

app = api("get", "/v1/apps?filter[bundleId]=#{BUNDLE_ID}")[1]["data"].first or abort "no app #{BUNDLE_ID}"
app_id = app["id"]

# The external group, made once.
groups = api("get", "/v1/apps/#{app_id}/betaGroups")[1]["data"] || []
group = groups.find { |g| g.dig("attributes", "name") == GROUP && !g.dig("attributes", "isInternalGroup") }
group ||= api("post", "/v1/betaGroups", { data: { type: "betaGroups", attributes: { name: GROUP },
  relationships: { app: { data: { type: "apps", id: app_id } } } } })[1]["data"]
puts "group #{GROUP} (#{group["id"]})"

# What testers and Beta App Review read: the description and where feedback goes.
locs = api("get", "/v1/apps/#{app_id}/betaAppLocalizations")[1]["data"] || []
loc = locs.find { |l| l.dig("attributes", "locale") == "en-US" }
attrs = { description: DESCRIPTION }
feedback = ENV["BETA_FEEDBACK_EMAIL"].to_s.strip
attrs[:feedbackEmail] = feedback unless feedback.empty?
if loc
  api("patch", "/v1/betaAppLocalizations/#{loc["id"]}", { data: { type: "betaAppLocalizations", id: loc["id"], attributes: attrs } })
else
  api("post", "/v1/betaAppLocalizations", { data: { type: "betaAppLocalizations", attributes: attrs.merge(locale: "en-US"),
    relationships: { app: { data: { type: "apps", id: app_id } } } } })
end
puts "test information set"

# Who Beta App Review contacts, and how it gets in without an account.
detail = api("get", "/v1/apps/#{app_id}/betaAppReviewDetail")[1]["data"]
review = { demoAccountRequired: false, notes: REVIEW_NOTES }
first, last, phone, email = ENV["BETA_CONTACT"].to_s.split(":", 4).map(&:strip)
if [first, last, phone, email].all? { |v| v && !v.empty? }
  review.merge!(contactFirstName: first, contactLastName: last, contactPhone: phone, contactEmail: email)
elsif detail.dig("attributes", "contactEmail").to_s.empty?
  abort "set the BETA_CONTACT secret (First:Last:phone:email): Beta App Review needs a contact"
end
api("patch", "/v1/betaAppReviewDetails/#{detail["id"]}", { data: { type: "betaAppReviewDetails", id: detail["id"], attributes: review } })
puts "beta review details set"

# The build, once App Store Connect has processed it (usually a few minutes).
number = ENV.fetch("BUILD_NUMBER")
build = nil
60.times do
  build = (api("get", "/v1/builds?filter[app]=#{app_id}&filter[version]=#{number}")[1]["data"] || []).first
  state = build&.dig("attributes", "processingState")
  break if state == "VALID"
  abort "build #{number} is #{state}" if %w[FAILED INVALID].include?(state)
  puts "build #{number}: #{state || "not there yet"}; waiting"
  sleep 30
end
abort "build #{number} wasn't processed in 30 minutes" unless build&.dig("attributes", "processingState") == "VALID"

api("post", "/v1/betaGroups/#{group["id"]}/relationships/builds", { data: [{ type: "builds", id: build["id"] }] })
puts "build #{number} added to #{GROUP}"

# Apple reviews it before external testers can install it; one already submitted or approved is fine.
status, = api("post", "/v1/betaAppReviewSubmissions",
  { data: { type: "betaAppReviewSubmissions", relationships: { build: { data: { type: "builds", id: build["id"] } } } } }, ok: [409, 422])
puts status < 400 ? "build #{number} submitted to Beta App Review" : "build #{number} needs no new review submission (#{status})"

# Testers: invited by email; Apple sends the invitation once a build in the group is approved.
ENV["TESTERS"].to_s.split(",").map(&:strip).reject(&:empty?).each_with_index do |entry, i|
  email, first, last = entry.split(":", 3).map(&:strip)
  found = (api("get", "/v1/betaTesters?filter[email]=#{URI.encode_www_form_component(email)}")[1]["data"] || []).first
  if found
    api("post", "/v1/betaGroups/#{group["id"]}/relationships/betaTesters", { data: [{ type: "betaTesters", id: found["id"] }] })
  else
    attrs = { email: email }
    attrs[:firstName] = first if first && !first.empty?
    attrs[:lastName] = last if last && !last.empty?
    api("post", "/v1/betaTesters", { data: { type: "betaTesters", attributes: attrs,
      relationships: { betaGroups: { data: [{ type: "betaGroups", id: group["id"] }] } } } })
  end
  puts "tester #{i + 1} is in #{GROUP}"
end
