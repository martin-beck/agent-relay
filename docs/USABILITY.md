# Human usability review

Automated semantic, accessibility, and visual tests establish whether a journey
behaves as designed. They do not establish whether a person can understand the
next safe action. Agent Relay therefore retains a small, repeatable human-review
record alongside the versioned journey contracts.

## Review protocol

The [usability review contract](contracts/usability-review-v1.json) defines the
review cadence, aggregate retention boundary, and thresholds for the same five
representative journeys in [`user-journeys-v1.json`](contracts/user-journeys-v1.json).
Each review records only:

- journey id and contract revision;
- completion, error, and time aggregates;
- a 1–7 workload score aggregate; and
- short, redacted theme labels linked to the review prompts.

Do not retain names, contact details, screen recordings, raw transcripts,
hostnames, paths, commands, session ids, or free-form notes that could identify a
person or protected work. Participants use synthetic fixtures and may stop at any
time. A review is a product signal, not a performance ranking.

The minimum review sample is five participants, with no participant completing
more than five journeys in one round. Reviewers report median completion time,
success rate, error rate, and workload score per journey. A journey is flagged
for design follow-up when any threshold is missed; the review does not silently
relax the interaction-cost budget in the journey contract.

## Validation

The contract is checked locally and in documentation CI:

```sh
uv run python scripts/docs/verify_usability_review.py
```

Evidence remains aggregate-only and expires after the retention period in the
contract. A future review report should include the contract revision and exact
application commit, but never participant identity or protected fixture data.
