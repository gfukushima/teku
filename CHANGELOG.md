# Changelog

## Upcoming Breaking Changes

## Current Releases

## Unreleased Changes

### Breaking Changes

### Additions and Improvements
 - Gossip signature verification now gathers larger batches and verifies signatures over the same message together, lowering CPU usage at the cost of up to ~10 ms added gossip validation latency. Use `--Xp2p-batch-verify-signatures-mode=LEGACY` to restore the previous batching behaviour. In both modes, signatures re-verified after a failed batch now honour `--Xp2p-batch-verify-signatures-strict-thread-limit-enabled` and a task that throws no longer fails the rest of its batch.

### Bug Fixes
 - `ExecutionPayloadEnvelopesByRange` and `ExecutionPayloadEnvelopesByRoot` requests from peers can no longer monopolise the execution engine. Envelopes not held in memory are only retained blinded and are unblinded with `engine_getPayloadBodiesByHash`, which is now served from its own small bounded queue instead of sharing the execution engine request queue, so a burst of such requests can no longer delay the `engine_newPayload` and `engine_forkchoiceUpdated` calls that block import and fork choice wait on; the new queue is observable through the `beacon_ee_payload_bodies_request_queue_size` and `beacon_ee_payload_bodies_request_queue_rejected` metrics. Envelopes are unblinded in batches of up to 32 with a single call each instead of one call per envelope, a request stops being served as soon as the peer closes the stream, the peer's envelope rate limit is charged for every slot or root looked up rather than only for the envelopes sent, and at most 16 envelope requests are served at once, two per peer, further ones being rejected with `RESOURCE_UNAVAILABLE`.
 - Reduced the memory held by gossip blocks waiting for validation, limited their total size, and blocks too far in the future are now ignored before they are decoded. [#11503](https://github.com/Consensys-Incorporated/teku/pull/11503)
 - The node's ENR no longer advertises a TCP port when the TCP transport is disabled (`--Xp2p-tcp-enabled=false`), so peers stop dialing a port nothing listens on. [#11454](https://github.com/Consensys-Incorporated/teku/issues/11454)
 - Block production now recovers within the slot when the `engine_forkchoiceUpdated` call it relies on fails, sending a new forkchoiceUpdated instead of returning the earlier failure. [#11488](https://github.com/Consensys-Incorporated/teku/pull/11488)
 - `ExecutionPayloadEnvelopesByRange` only serves envelopes of payloads that are part of the chain defined by the current head, as required by the spec. Envelopes of finalized blocks whose payload the chain did not build on are no longer retained, and such envelopes received while syncing are dropped instead of imported. Such envelopes already persisted by earlier versions are not removed, which only affects Gloas devnet nodes upgraded in place. [#11246](https://github.com/Consensys-Incorporated/teku/issues/11246)