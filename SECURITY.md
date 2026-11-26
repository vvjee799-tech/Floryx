# Security Policy

## Security Model

Floryx is a local-first autonomous AI agent for Android with access to sensitive system capabilities when explicitly enabled by the user:

- **API Keys & Secrets**: Stored locally using Android Keystore hardware-backed encryption (`KeystoreApiKeyStore`) and never transmitted to third-party telemetry servers.
- **Local Storage**: Conversations, checkpoints, memories, and usage records stay in the device's private Room SQLite database (`FlowChatDatabase`).
- **Sensitive Screen Protection**: Password fields, payment confirmation flows, verification code prompts, and `FLAG_SECURE` windows are blocked from screenshot capture and automated input/tap interactions.
- **User Takeover & Explicit Approval**: Returning to the Floryx app pauses active device automation; Android privileged system commands require explicit per-task confirmation.
- **Built-in Linux Environment**: Runs userspace Alpine Linux via PRoot inside app storage without requiring device Root.

## Reporting a Vulnerability

If you discover a security vulnerability in Floryx, please **do not** disclose it in a public GitHub issue while it remains unpatched.

- Prefer reporting privately via [GitHub Security Advisories](https://github.com/vvjee799-tech/Floryx/security/advisories/new) on this repository.
- Alternatively, you can send a private report via email to [`annyw333@gmail.com`](mailto:annyw333@gmail.com).
- Include a clear description of the issue, affected version/component, reproduction steps, and potential impact.

We will review reports promptly and coordinate a fix before public disclosure.
