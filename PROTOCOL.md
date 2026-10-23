# Phoneix protocol (v1)

Phone to PC over the local network. The PC side lives in Dynamic Bar (`phoneix.js`), the phone side in `android/`.

## Pairing
The PC shows a QR code containing `phoneix://pair?d=<base64url JSON>`:

```json
{ "v": 1, "n": "PC name", "h": ["192.168.1.45", "192.168.137.1"], "p": 47810, "k": "<base64 32-byte key>" }
```

The phone asks the user to confirm the PC name, then sends a `hello`. The PC keeps the new key as *pending*
and makes it permanent only after the first valid message, so a scan that never completes does not unpair the old phone.

## Messages
`POST http://<host>:<port>/phoneix/v1`, body = `base64( nonce(12) | ciphertext | tag(16) )`, AES-256-GCM, AAD `phoneix1`.
Plaintext is JSON with `t` (ms timestamp) and `type`:

| type | fields | effect |
|---|---|---|
| `hello` | `name` | pairing confirmation, shows "Telefon eşleşti" |
| `test` | `title`, `text` | shows a notification on the bar |

The reply (200) is encrypted with the same key, so the phone also authenticates the PC.
Anything else (wrong key, timestamp older than 60 s, repeated nonce, bad body, other paths) gets a bare 404.

## Limits
Body up to 64 KB, 40 requests per 5 s. The server only listens while a key exists or a pairing is waiting.
