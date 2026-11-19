# Security Policy

## Supported Versions

Only the latest release of Anyfy receives security updates. We strongly encourage all users and contributors to update to the latest version to ensure they have the latest security fixes.

| Version | Supported          |
| ------- | ------------------ |
| Latest  | :white_check_mark: |
| < Latest| :x:                |

---

## Reporting Vulnerabilities

If you discover a security vulnerability in Anyfy, please report it responsibly.

**Please do NOT open public GitHub issues, discussions, or pull requests for security vulnerabilities.**

Contact the maintainers privately at:
`[MAINTAINER_EMAIL_HERE]`

### What to Include in Your Report

To help us evaluate and address the issue efficiently, please include:
- **Description:** A detailed explanation of the vulnerability.
- **Reproduction Steps:** Step-by-step instructions or a minimal proof of concept (PoC) to reproduce the issue.
- **Impact:** An assessment of the potential impact, affected users, and attack scenarios.
- **Suggested Fix:** Any suggested fixes, patches, or mitigations (if available).

---

## Response Timeline

- **Acknowledgment:** We will acknowledge receipt of your vulnerability report within **48 hours**.
- **Fix Timeline:** The timeline for developing and releasing a fix depends on the severity and complexity of the issue. We will keep you updated on progress throughout the investigation.

---

## Contributor Security Rules

All contributors must follow these security practices when working on Anyfy:

- **Never Commit Secrets:** Never commit credentials, API keys, cookies, tokens, or signing keys to the repository.
- **Environment Variables & Secrets:** Always use environment variables for local configuration or GitHub Secrets for CI/CD workflows.
- **Keep Sensitive Files Ignored:** Critical files including `my-upload-key.jks`, `google-services.json`, and `.env` must stay in `.gitignore` at all times.
- **Report Accidental Commits Immediately:** If any secret, key, or sensitive credential is accidentally committed, report it immediately so keys can be revoked/rotated and Git history sanitized.

---

## Disclosure Policy

- **Coordinated Disclosure:** We adhere to coordinated disclosure principles. Vulnerabilities will be disclosed publicly only after a fix has been developed, tested, and released.
- **Attribution & Credit:** We are grateful for responsible disclosure. Reporters will be credited in security advisories and release notes unless anonymity is requested.
