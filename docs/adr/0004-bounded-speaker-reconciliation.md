# ADR-0004: Bounded speaker evidence reconciliation

Status: implemented, device calibration pending (2026-10-09).

## Decision

Keep identity tracking in the single speaker worker. Retain at most 256 normalized vectors, never PCM or embeddings in Room or exported diagnostics. Reconcile every 16 accepted observations with at most one topology change per checkpoint and a 64-observation cooldown. Invalid or ambiguous evidence stays unknown.

Merge only confirmed clusters with at least four recent observations per cluster, centroid cosine >= 0.90 and reciprocal / combined cohesion >= 0.82. Retain the older allocated label. Publish a separate session-scoped SpeakerMerge before per-segment assignments; SubtitleReducer canonicalizes all historical and pending labels without replacing transcript or translation fields.

Split via four fixed cosine two-means iterations. Both modes need four observations, cosine <= 0.45, cohesion >= 0.82, and assignment margin >= 0.08. The original immutable anchor must prefer one mode by the same margin to retain its label. Only retained evidence can be reassigned. Older segments cannot be reconstructed after their vectors are evicted.

Use monotonically increasing speaker revisions for the entire session. Allocate labels once, including retired labels, with a lifetime cap of 16; do not reuse a historical identity. Aliases therefore remain bounded. Clear evidence after the worker exits, after any in-flight native call has returned. Measure tracking separately from native embedding extraction.

## Consequences

Synthetic vector and reducer tests cover convergence, separation, old-history merging, delayed events, cycles, revision ordering, dimension / duration rejection, capacity, and bounded retention. These tests do not establish human identity accuracy, CAM++ cross-language invariance, overlapping speech recovery, or measured performance. Thresholds require device and licensed human-speech calibration before a stable release.

The conservative all-evidence gates can leave imperfect clusters unchanged. Finite label allocation can leave new voices unknown in unusually long or unstable sessions. This is preferable to silently reusing retired labels or making unsupported revisions outside the evidence window.


## 2026-10-26 First-evidence assignment

VAD now schedules embedding independently of ASR, with an initial 0.8-second evidence gate, one-second retries for unresolved segments, a two-second rolling speculative window and a full-segment fallback. The bounded speaker queue closes with the frontend. A clear novel vector is labelled immediately; a second distinct segment remains necessary for confirmed-cluster reconciliation. Duplicate accepted segment IDs never add confirmation evidence. Match, novelty, ambiguity, merge/split, cooldown and label-cap rules are unchanged. Short, invalid or ambiguous observations remain unknown. This reduces software waiting, without asserting measured CAM++ accuracy or intra-segment turn segmentation.
