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
| `notif` | `app`, `title`, `text` | a notification from an app the user picked on the phone; shown on the bar |
| `test` | `title`, `text` | shows a notification on the bar |

The reply (200) is encrypted with the same key, so the phone also authenticates the PC.
Anything else (wrong key, timestamp older than 60 s, repeated nonce, bad body, other paths) gets a bare 404.

## Limits
Body up to 64 KB, 40 requests per 5 s. The server only listens while a key exists or a pairing is waiting.

## Files
Chunks are 1 MB; request bodies up to 4 MB. All of it goes through the same encrypted `POST`.

| type | direction | fields | reply |
|---|---|---|---|
| `put` | phone → PC | `id` (hex), `name`, `size`, `seq`, `last`, `data` (base64) | `ok`. Chunks must arrive in order; the file is saved to the PC's receive folder (default `Downloads\Phoneix`) |
| `poll` | phone → PC | none | long poll (held up to 25 s): `files: [{id, name, size}]` waiting on the PC |
| `get` | phone → PC | `id`, `seq` | `data` (base64), `last`, `size` |
| `done` | phone → PC | `id` | `ok` |

## Local API (Windows, for Dynamic Bar and scripts)
The desktop app listens on `127.0.0.1:<random port>` and writes `~/.dynbar-ecosystem/phoneix.json`
(`{app, port, token, pid, paired, phone}`). `POST /send {"paths":[...]}` and `GET /job/<id>` need `Authorization: Bearer <token>`.
Dynamic Bar shows a "Telefon" parcel destination while this file points to a live process with a paired phone.

## Reply, calls and commands
The poll reply also carries `cmds` (short-lived, at most 30 s): `{type:"clip",text}`, `{type:"ring"}`, `{type:"stopring"}`, `{type:"reply",id,text}`, `{type:"call",action:"answer|reject|hangup|speaker"}`.

| type | direction | fields |
|---|---|---|
| `clip` | phone → PC | `text` (PC clipboard) |
| `notif` | phone → PC | now also `reply` (id) when the notification has a free-text reply action |
| `replyres` | phone → PC | `id`, `ok`, `error` |
| `call` | phone → PC | `state` (`ringing`, `active`, `ended`), `who`, `speaker` |
| `poll` | phone → PC | also `battery`, `charging` |
