# Load redistribution

`LoadRedistributor.kt` moves endpoints away from a bridge. It has two uses:

- An HTTP API. `ktor/Application.kt` exposes the GET routes `/move-endpoints/move-endpoint`, `/move-endpoints/move-endpoints` and `/move-endpoints/move-fraction` when `jicofo.rest.move-endpoints.enabled` is true (the default).
- An automatic mode, which `jicofo.bridge.load-redistribution.*` controls. The defaults are in `jicofo-selector/src/main/resources/reference.conf`.

## Automatic mode

When `load-redistribution.enabled` is true, a task runs every `interval`:

1. If no bridge is below the bridge-level threshold (`BridgeSelector.hasNonOverloadedBridge()`), the run stops.
2. For each bridge that is not in timeout and has `correctedStress >= load-redistribution.stress-threshold`, the task puts the bridge in timeout for `timeout`. Then it moves `endpoints` endpoints away from the bridge.

Keep `load-redistribution.stress-threshold` higher than `jicofo.bridge.stress-threshold`.

## How endpoints are moved

`moveEndpoints` and `moveFraction` sort the conferences on the bridge by their endpoint count on that bridge, largest first. They take endpoints greedily from that list until they reach the count. For each conference, `JitsiMeetConferenceImpl.moveEndpoints(bridge, numEps)` takes the first `numEps` IDs from `ColibriSessionManager.getParticipants(bridge)`, removes them from the Colibri session, and invites them again with `reInviteParticipantsById`. Each new invite runs a new bridge selection.
