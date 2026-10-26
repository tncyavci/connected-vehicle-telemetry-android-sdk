# ADR-001: Asymmetric Ed25519 Signing and ISO 26262 Safety Interlocks for Remote Vehicle Actuation

- **Status:** Accepted
- **Date:** 2026-10-26

## Context
Connected vehicle mobile applications allow drivers to execute physical actuations on the vehicle (unlocking doors, opening the trunk, starting HV battery conditioning/HVAC) over untrusted cellular networks. Relying solely on bearer tokens (OAuth2/JWT) exposes the Telematics Control Unit (TCU) gateway to replay attacks, accidental actuation while the vehicle is moving, and HV battery depletion.

## Decision
1. **Pre-Signing Safety Interlock (`CommandSafetyPolicyEngine`):** Enforce deterministic client- and gateway-side safety rules before any command is signed or transmitted:
   - Stationary interlock (`speedKmh <= 0.5` and `gearPosition == PARK`) for `UNLOCK_DOORS` and `OPEN_TRUNK`.
   - Minimum High-Voltage State of Charge (`batterySocPercent >= 15.0%`) for `START_HVAC_PRECONDITIONING`.
   - Strict TTL (`<= 60s`) and nonce replay tracking.
2. **Ed25519 Payload Signing (`Ed25519VehicleCommandSigner`):** Sign a canonical pipe-delimited command representation using Google Tink Ed25519 keys. Attach both `Idempotency-Key` and `X-TCU-Signature-Ed25519` headers on Ktor requests.

## Consequences
- **Positive:** Compromised relay proxies cannot alter the target VIN or actuation type without invalidating the Ed25519 signature; unsafe actuations during driving are deterministically blocked.
- **Negative:** Requires synchronized device/gateway clocks within the configured `maxClockSkewMs` (5,000ms) window.
