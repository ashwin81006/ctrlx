
# CTRLX Protocol v1

## Transport
- TCP connection.
- UTF-8 encoded, newline-delimited JSON.
- Maximum message size: 4096 bytes.
- Protocol version: 1.

## Handshake

Client:
{"type":"HELLO","version":1}

Server:
{"type":"WELCOME","version":1}

An invalid handshake receives an ERROR response and the connection closes.

## Heartbeat

Client:
{"type":"PING"}

Server:
{"type":"PONG"}

## Errors

Server:
{"type":"ERROR","message":"Unsupported message"}

## Current scope
Only HELLO and PING are supported.
No remote-control commands are enabled.
