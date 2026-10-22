# CTRLX Protocol v1.1

## Transport
- TCP connection.
- UTF-8 encoded, newline-delimited JSON.
- Maximum message size: 4096 bytes (strictly enforced).
- Protocol version: 1.

## Connection Lifecycle & State Machine
1. **Connected (Unauthenticated)**:
   - Handshake required (`HELLO` -> `WELCOME`).
   - Allowed operations: `PING`, `PAIR_REQUEST`, `PAIR_VERIFY`, `AUTH`.
   - Control commands and `STATS` return `AUTH_REQUIRED`.
2. **Authenticated**:
   - Reached after successful `PAIR_VERIFY` or `AUTH`.
   - Allowed operations: `PING`, `STATS`, `UNPAIR`, and control commands.

---

## Handshake
Client:
```json
{"type":"HELLO","version":1}
```
Server:
```json
{"type":"WELCOME","version":1}
```
*An invalid handshake receives an ERROR response and the connection closes.*

---

## Heartbeat (Allowed Unauthenticated & Authenticated)
Client:
```json
{"type":"PING"}
```
Server:
```json
{"type":"PONG"}
```

---

## Device Pairing Flow (2-Step Verification)
Used when a client connects for the first time.

### 1. Request Pairing
Client:
```json
{"type":"PAIR_REQUEST","deviceId":"550e8400-e29b-41d4-a716-446655440000","deviceName":"Pixel 8"}
```
Server generates a 6-digit random PIN displayed on the PC screen and responds:
```json
{"type":"PAIR_PIN_REQUIRED","expiresIn":60}
```

### 2. Verify Pairing PIN
The user reads the PIN from the PC screen and enters it into the mobile client.
Client:
```json
{"type":"PAIR_VERIFY","deviceId":"550e8400-e29b-41d4-a716-446655440000","pin":"492817"}
```
Server verifies the PIN. On success:
```json
{"type":"PAIR_SUCCESS","token":"<cryptographically_secure_auth_token>"}
```
*On failure, server returns `INVALID_PIN`, `PIN_EXPIRED`, or `TOO_MANY_ATTEMPTS`.*

---

## Authentication (Returning Paired Devices)
Client connects, performs handshake, then sends:
```json
{"type":"AUTH","deviceId":"550e8400-e29b-41d4-a716-446655440000","token":"<auth_token>"}
```
Server verifies token hash. On success:
```json
{"type":"AUTH_SUCCESS"}
```
*On failure, server returns `AUTH_FAILED` and terminates the connection.*

---

## Unpair / Revoke
Authenticated Client:
```json
{"type":"UNPAIR"}
```
Server responds:
```json
{"type":"UNPAIR_SUCCESS"}
```

---

## Server Statistics (Requires Authentication)
Client:
```json
{"type":"STATS"}
```
Server:
```json
{"type":"STATS","connectedClients":1,"uptimeSeconds":120,"pairedClients":1}
```

---

## Xbox Gamepad Controls (Requires Authentication)

### 1. Gamepad Button State (Press & Release)
Client:
```json
{"type":"GAMEPAD_BUTTON","button":"A","state":"DOWN"}
```
Supported buttons:
- `A`, `B`, `X`, `Y` (Standard Xbox action buttons)
- `LB`, `RB` (Left & Right Bumpers)
- `LT`, `RT` (Left & Right Triggers)
- `DPAD_UP`, `DPAD_DOWN`, `DPAD_LEFT`, `DPAD_RIGHT`
- `START` (Menu / Pause), `BACK` (View / Map / Scoreboard)
- `XBOX` (Xbox Guide button - triggers Windows Game Bar)
- `L3`, `R3` (Thumbstick clicks)

States: `"DOWN"`, `"UP"`

### 2. Gamepad Analog Thumbsticks
Client:
```json
{"type":"GAMEPAD_AXIS","stick":"LEFT","x":-0.75,"y":0.82}
```
- `stick`: `"LEFT"` (Character movement / WASD) or `"RIGHT"` (Camera aim / Mouse look)
- `x`, `y`: Float values between `-1.0` and `1.0`

### 3. Analog Triggers
Client:
```json
{"type":"GAMEPAD_TRIGGER","trigger":"RT","value":0.85}
```
- `trigger`: `"LT"` (Left Trigger) or `"RT"` (Right Trigger)
- `value`: Float between `0.0` (unpressed) and `1.0` (fully depressed)

### 4. Gamepad Profile Selection
Client:
```json
{"type":"GAMEPAD_PROFILE","profile":"FPS"}
```
Profiles: `"FPS"`, `"RACING"`, `"RETRO"`

---

## Error Responses
```json
{"type":"ERROR","code":"AUTH_REQUIRED","message":"Authentication required"}
```
Supported error codes:
- `AUTH_REQUIRED`
- `AUTH_FAILED`
- `INVALID_PIN`
- `PIN_EXPIRED`
- `TOO_MANY_ATTEMPTS`
- `NO_PENDING_REQUEST`
- `INVALID_ARGUMENTS`
- `INVALID_DEVICE_ID`
- `INVALID_HANDSHAKE`
- `INVALID_JSON`
- `MISSING_TYPE`
- `PAYLOAD_TOO_LARGE`
- `UNSUPPORTED_MESSAGE`
