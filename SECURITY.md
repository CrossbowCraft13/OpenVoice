# Security Policy

## Security Model

OpenVoice is designed with a **zero-trust, privacy-first** security model:

- **All processing is local** — No data ever leaves your device
- **No cloud services** — No API keys, no server endpoints, no telemetry
- **AES-256-GCM encryption** — All stored memories are encrypted at rest
- **Android Keystore** — Encryption keys are hardware-backed and never exported
- **Biometric option** — Sensitive memories can require biometric authentication
- **No network requests** — The app makes no hidden network connections
- **No analytics** — No tracking, no crash reporting, no usage statistics
- **Open source** — Every line of code is visible and auditable

## Threat Model

### Protected Against

| Threat | Mitigation |
|--------|------------|
| Cloud data breach | No cloud services exist |
| Telemetry / tracking | Zero networking code |
| Unauthorized local access | AES-256-GCM encryption via Android Keystore |
| Memory inspection | Encrypted at rest. Key never exported. |
| Network surveillance | No network communication at all |
| Supply chain attack | Open source, reproducible builds |
| Permission abuse | Each permission has a clear, documented purpose |
| Accessibility misuse | All A11y actions are user-initiated, never automated without consent |

### Not Protected Against (In Scope)

| Limitation | Reason |
|------------|--------|
| Physical device theft | Device-level encryption is Android's responsibility |
| Rooted devices | Root access can read any app's memory |
| Malware on device | Malware with root access can compromise any app |

## Permissions

OpenVoice requires the following permissions:

| Permission | Required | Purpose |
|-----------|----------|---------|
| `RECORD_AUDIO` | Yes | Voice command capture. Processed locally. Never stored or transmitted. |
| `POST_NOTIFICATIONS` | Yes | Foreground service notification for background operation |
| `BIND_ACCESSIBILITY_SERVICE` | No | Screen reading and automation. User-enabled only. |
| `SEND_SMS` | No | Sending text messages on your behalf |
| `CALL_PHONE` | No | Making phone calls. Opens dialer by default. |
| `READ_CONTACTS` | No | Resolving contact names from phone numbers |

## Data Storage

| Data | Storage | Encryption | Access |
|------|---------|------------|--------|
| Memories | SQLite + Vector index | AES-256-GCM | User-inspectable, deletable |
| Knowledge graph | SQLite | AES-256-GCM | User-inspectable, deletable |
| Preferences | DataStore / SharedPrefs | Not encrypted (no PII) | User-editable |
| Models | App-private files | Not encrypted (public models) | Manageable via UI |
| Audio buffer | In-memory only | Not persisted | Discarded after processing |

## Reporting a Vulnerability

If you discover a security vulnerability in OpenVoice, please:
1. **Do not** open a public GitHub issue
2. Email the maintainers directly
3. Include a detailed description and proof of concept if possible

You can expect:
- Acknowledgment within 48 hours
- A fix timeline within 7 days
- Credit in release notes (if desired)

## Responsible Disclosure

We ask that you:
- Give us reasonable time to fix the issue before public disclosure
- Do not exploit the vulnerability
- Act in good faith to improve the security of open-source AI

Thank you for helping keep OpenVoice and its users safe.
