# Security Policy

QuotaDog is a local-first client that handles OAuth tokens for third-party
provider accounts (Codex, Claude Code). Optional Dropbox sync encrypts QuotaDog
sync data client-side before uploading it to the user's Dropbox app folder. We
take security reports seriously and appreciate responsible disclosure.

## Supported versions

Only the latest commit on `main` is supported. There are no LTS branches.

## Reporting a vulnerability

Please **do not** file public GitHub issues, discussions, or pull requests
for security problems. Instead use one of the private channels below:

- GitHub Security Advisories: open a private advisory on this repository's
  **Security** tab → **Report a vulnerability**. This is the preferred
  channel.
- Email: [quotadog@saien.pro](mailto:quotadog@saien.pro). Please put
  `[security]` in the subject line.

When reporting, please include:

- A clear description of the issue and its impact.
- Step-by-step reproduction instructions.
- Affected platform(s) (Android / desktop / iOS) and commit hash.
- Any proof-of-concept code or logs.

**Important:** redact tokens, refresh tokens, Dropbox app keys, encrypted sync
payloads, OAuth callback URLs, account identifiers, email addresses, and any
other personal data before sending reproduction details. If a redacted
reproduction is not possible, say so in the report and we will coordinate a
secure way to share the data.

## What to expect

- We will acknowledge receipt as soon as we can, typically within a few
  days.
- We will work with you on a fix and a disclosure timeline. Please give us
  reasonable time to ship a fix before any public disclosure.
- Credit will be given in the release notes if you would like.

## Out of scope

- Issues caused by changes to provider-side behavior (Codex / Anthropic
  endpoints, rate limits, ToS) rather than QuotaDog itself.
- Loss of access caused by a forgotten Dropbox sync passphrase. QuotaDog does
  not store or recover the passphrase.
- Theoretical issues without a practical attack path against a QuotaDog
  user.
- Vulnerabilities in dependencies that do not affect QuotaDog as shipped.

Thank you for helping keep QuotaDog and its users safe.
