# Outcome-first onboarding

The first-run journey starts with the user's desired outcome. It produces either a
reviewable workflow draft or a durable explanation of why onboarding stopped.

The reducer keeps only a bounded, user-visible outcome and workflow title. It rejects
credentials and raw endpoints before they enter navigation or analytics state. Resume is
idempotent, revisions advance only on accepted transitions, and the interaction budget is
bounded so an interrupted journey cannot loop indefinitely.

The stages are `WELCOME`, `OUTCOME`, `SAFETY_REVIEW`, `WORKFLOW_REVIEW`, `COMPLETE`, and
`BLOCKED`. A blocked state carries an actionable reason and never retains the rejected text.
