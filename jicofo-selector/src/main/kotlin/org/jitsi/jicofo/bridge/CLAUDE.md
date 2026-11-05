# Bridge selection

This file describes how jicofo selects a bridge for a new endpoint. For the defaults of all `jicofo.bridge.*` options, see `jicofo-selector/src/main/resources/reference.conf`.

## Key files

- `Bridge.kt`: the state of one bridge, the stress calculation, and the sort order.
- `BridgeSelector.kt`: filters the candidate bridges and calls the strategy.
- `BridgeSelectionStrategy.kt`: the base class of the strategies, with the helper methods and the per-conference overload check.
- `RegionBasedBridgeSelectionStrategy.kt`: the strategy for deployments with more than one region.
- `BridgeConfig.kt`: the `jicofo.bridge.*` configuration.

## Stress model

Each bridge reports `stress_level` and `average_participant_stress` in its presence, every 5 to 10 seconds. All selection decisions use `Bridge.correctedStress`:

```
correctedStress = lastReportedStressLevel
                + recentlyAddedEndpointCount * averageParticipantStress
```

- `recentlyAddedEndpointCount` is the number of endpoints that this jicofo added to the bridge in the last `participant-rampup-interval`, across all conferences.
- `averageParticipantStress` is the value that the bridge reports. If the bridge reports no value, it is `average-participant-stress`.
- While the bridge is failing ICE, `correctedStress` is at least `stress-threshold + 0.01`.

The reported stress lags. During a burst of joins, `lastReportedStressLevel` can stay the same for several seconds while jicofo keeps selecting the same bridge. In that window, only the `recentlyAddedEndpointCount * averageParticipantStress` term prevents overshoot, and that term is an estimate, not a measurement.

A bridge that already had load from other conferences before the burst overshoots the most. Its next report includes the baseline load plus all the endpoints that jicofo added during the lag, at the same time.

## Overload checks

There are two overload checks. Do not mix them up.

- `Bridge.isOverloaded` (bridge level): `correctedStress >= stress-threshold`. `BridgeSelector.hasNonOverloadedBridge()` and step 6 of the strategy chain use this check.
- `Bridge.isOverloaded(conferenceBridges)` (private, in `BridgeSelectionStrategy.kt`): the bridge-level check, OR the conference has `max-bridge-participants` endpoints on the bridge, OR the conference reached `max-bridge-participants-per-interval` on the bridge. Steps 1 to 5 of the strategy chain use this check.

## Candidate order and filtering

`Bridge.compareTo` sorts bridges in this order:

1. Operational and not in graceful shutdown.
2. Operational and in graceful shutdown.
3. Not operational.

In each group, a lower `correctedStress` comes first.

`BridgeSelector.selectBridge` filters the sorted list before it calls the strategy:

1. Keep only operational bridges.
2. Remove bridges that are `SHUTTING_DOWN`.
3. If `OctoConfig.allowMixedVersions` is false, keep only bridges with the required version. The required version is the version of the bridges that the conference already uses. If the conference has no bridge yet, it is the `version` argument of `selectBridge`, if the caller gives one.
4. Prefer bridges that are not draining, if there are any.
5. Prefer bridges that are not in graceful shutdown, if there are any.

If step 1, 2 or 3 leaves no bridge, the selection fails. If the caller gives a `version` that is different from the version of the bridges of the conference, the selection fails before step 1.

## RegionBasedBridgeSelectionStrategy

`doSelect` returns the first match of this chain:

1. Not overloaded, in the conference, in the exact region.
2. Not overloaded, in the conference, in the region group.
3. Not overloaded, in the exact region. When the conference already uses a different bridge, this splits the conference.
4. Not overloaded, in the region group.
5. Not overloaded, in the conference, any region.
6. Not overloaded at bridge level, any region (`notLoaded`).
7. In the conference and below `max-bridge-participants` (`leastLoadedNotMaxedAlreadyInConference`).
8. The least loaded candidate (`leastLoaded`).

Steps 1 to 5 use the per-conference overload check.

A participant with no region is always treated as if it were in `localRegion`.

The next two rules apply only when `localRegion` is set:

- If the conference has no bridge yet, a participant whose region is in the same region group as the local region (but is not the local region) gets a bridge in the local region. The strategy assumes that the next participant is in the local region.
- If no candidate bridge is in the region group of the participant, the strategy selects as if the participant were in `localRegion`.

`jicofo.bridge.region-groups` defines the region groups. A region that is in no group forms a group of its own.

## Per-conference join-rate limit

`max-bridge-participants-per-interval` limits how fast one conference can add endpoints to one bridge. It reduces the burst overshoot that the stress model section describes.

- The limit applies to one (bridge, conference) pair. It is a term in the per-conference overload check only. It does not change `correctedStress` or `Bridge.isOverloaded`, so it has no effect on other conferences, on sorting, on `hasNonOverloadedBridge()`, or on load redistribution.
- The state is a `RateTracker` in `Colibri2Session`, which exists once for each (conference, bridge) pair. The tracker is created and removed together with the session. Do not move it to `Bridge` or to a separate map.
- `ColibriV2SessionManager.getBridges()` reads the tracker of each session into `ConferenceBridgeProperties.recentlyAddedParticipantCount` for each selection.
- `getBridges()` also drops the sessions whose bridge is not operational. So a bridge that is not operational is never in `conferenceBridges`, even when the conference has endpoints on it.
- The default is `-1` (disabled).
- Only steps 1 to 5 of the strategy chain honor the limit. Step 6 (`notLoaded`) uses only the bridge-level check. Step 7 (`leastLoadedNotMaxedAlreadyInConference`) checks only `max-bridge-participants`. Step 8 (`leastLoaded`) checks nothing. Steps 7 and 8 return some bridge, so that the allocation does not fail.
- Load redistribution removes each moved endpoint and invites it again. The move counts as a new add on the destination bridge, so moved endpoints can reach the limit there.

## ICE failure detection

When `ice-failure-detection.enabled` is true (default false), `Bridge.endpointRequestedRestart()` marks the bridge as failing ICE if the bridge has at least `min-endpoints` endpoints and the restart requests in the last `interval` are more than `threshold` times the endpoint count. For `timeout` after the last detection, `correctedStress` is at least `stress-threshold + 0.01`, so the bridge counts as overloaded.
