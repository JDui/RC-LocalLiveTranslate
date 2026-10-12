# ADR-0007: Bounded session journal and paged history

Status: implemented; host validation recorded in docs/11_VALIDATION.md; device validation pending.

## Decision

Separate a 500-row live reducer from durable history. Submit events through a 128-command IO journal with batches of up to 32 commands / 250 ms. Save caption rows keyed by session and segment, preserving independent revisions and speaker aliases. Fetch evicted rows individually for late corrections; apply speaker merges with SQL. Backpressure is limited to inference workers, never AudioRecord. Explicitly stop capture on persistence failure.

Room v2 adds a cascading caption table without rewriting v1 blobs at database open. Convert legacy records on demand with a bounded Reader and batched idempotent upserts; clear the original blob only on complete success. Query only 50 metadata summaries at a time, read 200 captions per detail page, and stream all export formats via keyset pages.

Disable retention immediately and advance a generation before the settings write. Clear the ongoing journal and reject queued events from older generations, including across a rapid reenable. Finished records remain until explicitly deleted. Keep model identity, JNI configuration, token caps, capture and translation scheduling unchanged. Translation work retains text and identifiers, not PCM.

## Consequences

UI and journal memory have explicit cardinality limits while retained history grows on disk. Up to a current batch of data can be lost if the OS kills the process; already committed rows remain. No automatic history expiry, destructive migration or automatic file logging is added. Room deletion does not guarantee physical erasure. Queued events carry variable-length text, so cardinality caps are not byte-accurate memory limits; production transcript size is governed by the existing inference limits.

Host tests cover migration, Unicode chunk boundaries, pagination, export equivalence, late revisions, long journals, speaker merging and privacy generations. Native leaks, flash endurance, thermal behavior, real microphone responsiveness and model latency still require device evidence.
