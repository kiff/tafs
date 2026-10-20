# Security policy

## Report privately

Report a suspected vulnerability using
[GitHub private vulnerability reporting](https://github.com/kiff/tafs/security/advisories/new)
or email [security@kiff.dev](mailto:security@kiff.dev), the contact published in
[KIFF's security policy](https://kiff.dev/security).

Do not put exploit details, credentials, customer data, or private infrastructure
information in public issues, pull requests, CI logs, or assistant-session links.
Use public issues for non-sensitive bugs.

Include the affected commit or release, impact, prerequisites, and a minimal
reproduction using synthetic data. Explain expected and observed behavior.
Share redacted logs only; never send live tokens, passwords, or signing keys.
Test only on systems and accounts you own or have permission to assess.

## Scope

Relevant boundaries include the terms shown and signed on the device,
authentication and account isolation, approvals and revocation, chat and memory
privacy, job execution, and accurate reporting of execution outcomes.

Report the exact deployed revision when hosted behavior differs from main.
For a problem spanning KIFF, planque, tafs, or kiff/agents, explain the boundary
privately so maintainers can coordinate the fix. Repository visibility is not
permission to test against the hosted service or another person's account.

## Maintenance and disclosure

Development currently follows main. No older release support schedule is
published; report the affected version so maintainers can assess it explicitly.

Maintainers should acknowledge the report privately, reproduce and assess it,
coordinate fixes and rollout across affected services, and agree disclosure
with the reporter. Keep sensitive regressions and patches private until a safe
release or mitigation is available. Publish an advisory and changelog entry
when disclosure is appropriate; do not treat a merged patch as proof that every
affected deployment is fixed.
