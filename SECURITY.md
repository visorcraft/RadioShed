# Security Policy

RadioShed is a private, proprietary application by VisorCraft LLC — a
station-centric music app for a local library, targeting Android, Windows,
Linux, and iOS, built with Kotlin Multiplatform. This policy covers how to
report a security issue in RadioShed.

## Supported versions

Security fixes target the latest `master`. There are no supported release
branches; older builds receive fixes only when a patch is trivial to carry
forward.

## Reporting a vulnerability

**Do not open a public GitHub issue, discussion, or pull request for security
problems.** Report privately through a **GitHub security advisory**:

1. Go to the repository's **Security** tab.
2. Open **Advisories** → **New draft security advisory**.
3. Describe the issue with the details below.

If you don't have access to create an advisory, contact a RadioShed maintainer
privately through the repository rather than filing a public report.

Please include as much as you can:

- a description of the issue and its impact,
- step-by-step reproduction steps,
- the RadioShed version and the platform (Android / Windows / Linux / iOS) and
  OS version,
- the relevant configuration, logs, or a proof-of-concept,
- a suggested fix or mitigation, if you have one.

## What to expect

- **Acknowledgement** of your report within a few days.
- An initial assessment and, where confirmed, a remediation plan.
- Progress updates through the private advisory thread until the issue is
  resolved.
- Credit for your responsible disclosure in the advisory, unless you prefer to
  remain anonymous.

We ask that you give us a reasonable opportunity to ship a fix before any
public disclosure.

## Scope

RadioShed runs locally and keeps your library under your own control. The most
relevant concerns are local data handling, the optional metadata fetch, and
third-party dependencies. Vulnerabilities in the Kotlin / Compose Multiplatform
toolchain or in third-party libraries should be reported to their respective
upstream projects — you may still tell us so we can update the affected
dependency.
