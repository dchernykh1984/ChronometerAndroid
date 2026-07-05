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
cutoff. The server must accept only the newest revision for a `(device_id,
point_number)` stream and reject stale snapshots, so retries or duplicated
network deliveries cannot create duplicated cutoffs.
