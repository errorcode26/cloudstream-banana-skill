# Security Policy

## Supported Versions

Only the latest commit on the `main` or `master` branch is actively supported with security updates.

| Version | Supported |
| :--- | :--- |
| main / master | Yes |
| Older commits | No |

---

## Scope & Purpose

This repository provides open-source Kotlin scaffolding templates, reference documentation, and Python diagnostic tooling for CloudStream 3 extensions. It does not operate backend servers, databases, or user-facing cloud infrastructure.

Security considerations focus primarily on:
* Client-side execution safety within Android application runtimes.
* Safe HTML DOM and JSON parsing without arbitrary remote code execution (RCE).
* Prevention of credential leakage (e.g. hardcoded API keys, session tokens, or private secrets).

---

## Reporting a Vulnerability

If you discover a security vulnerability, please report it responsibly rather than opening a public issue.

1. Submit a private report via GitHub Private Vulnerability Reporting (Security tab -> "Report a vulnerability").
2. Include detailed reproduction steps, proof-of-concept code, and the affected file paths.
3. Allow reasonable time for the maintainers to investigate and patch the issue before any public disclosure.

---

## Code Safety Rules

* Never commit personal API tokens, private signing keys, or authentication cookies.
* Ensure all network calls use secure transport (HTTPS) and validate certificates.
* Avoid unsafe reflection or unconstrained dynamic code execution inside Android DEX binaries.
