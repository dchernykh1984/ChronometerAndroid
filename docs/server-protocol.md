# Server protocol

## Status

Accepted. The Android app mirrors the upload contract used by
`WindowsChronometerPython`.

## Endpoints

- Finish point (`pointNumber == 0`):
  `POST <siteUrl>/api/v1/finish-times/`
- Remote point (`pointNumber >= 1`):
  `POST <siteUrl>/api/v1/remote-points/`

Only `http` and `https` site URLs are accepted by the client.

## Authentication

The competition token is sent in the JSON body as `competition_token`.
The client does not use a `Bearer` authorization header.

## Payload

Common fields:

```json
{
  "competition_token": "competition-token",
  "device_id": "device-uuid",
  "items": ["70123#12:34:56.789#nextLap#"],
  "client_revision": 42
}
```

Remote-point uploads also include:

```json
{
  "point_number": 3
}
```

Each item is a protocol line in the existing desktop-tool format:
`number#time#event#`.

## Idempotency

The client sends the whole current snapshot for the device and point, not
per-cutoff mutations. `client_revision` is incremented after every recorded
cutoff. The server must accept only the newest revision for a
`(competition_token, device_id, point_number)` stream and reject stale
snapshots, so retries or duplicated network deliveries cannot create duplicated
cutoffs.

The `competition_token` is part of the idempotency key on purpose. "New
competition" (settings screen) resets `client_revision` to 0 and is used
together with a new competition token, so the next competition's revisions
(1, 2, ...) form a fresh stream and are never treated as stale relative to a
previous competition on the same device and point. A server that keyed only on
`(device_id, point_number)` would reject the new competition's early revisions
with HTTP 409 (`UploadResult.GIVE_UP`); keying on the token as well is
therefore required.
