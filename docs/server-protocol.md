# Server protocol

## Status

Accepted. The Android app mirrors the upload contract used by
`WindowsChronometerPython`.

## Start list download

"Download start protocol" in the settings, and nothing else, asks the site for
the start list, with the request FinishProtocolGenerator and the desktop
chronometer make:

`GET <siteUrl>/api/v1/start-list/?competition_token=<token>`

The answer is a JSON object whose `devices` list holds objects whose `items` are
start protocol lines, as each StartProtocolMaker device uploaded them:

```json
{"devices": [{"device_id": "maker-uuid", "items": ["15#Rider#M40#3#1#1990#T#C##0 00:00:00.000#r1#E200A1,E200A2#"]}]}
```

The lines of every device are taken in the order the site returns them, and a
blank line is no rider. The answer is refused when it is not such an object,
when `devices` is not a list, or when a line is anything but text on one line;
a list with no lines is refused as "no start list yet". A refused answer, an
HTTP error or no connection changes nothing. Otherwise the previous `start.txt`
is kept as `backup/start-<millis>.txt`, the new one replaces it in one step and
the chip map is rebuilt from it.

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

`client_revision` never goes back, not even on "New competition" (settings
screen). A referee may start over and enter the same competition's token again
to overwrite what the device sent: a counter restarted from 1 would sit below
the revision the server already holds for that stream, and every upload would
be refused with HTTP 409 (`UploadResult.GIVE_UP`) until it climbed past it. The
counter is a 64-bit integer, so one device never runs out of revisions.
