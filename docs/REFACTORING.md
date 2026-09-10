# Verified refactoring protocol

This protocol governs deterministic, reviewable source refactoring. It does not
authorize feature work, bug repair, dependency changes, or arbitrary search and
replace. Every recipe is catalogued, version-pinned, fixture-tested, and
confined to an isolated claimed worktree.

## Risk and authority

- **R0** covers deterministic formatting and trusted built-in fixes.
- **R1** covers tested mechanical transformations with no intentional behavior
  or public-contract change.
- **R2** covers API, module, persistence, concurrency, security, UI, or other
  observable behavior risk. It requires explicit merge authorization.
- **R3** covers transformations whose semantic equivalence is not bounded. It
  is investigation-only and cannot be applied.

Only allowlisted R0 and R1 recipes may become autonomous after their engine and
integration tasks are complete. A catalog entry is not an authorization to run
it. The coordinator claim, isolated worktree, exact base revision, and required
gates remain mandatory for every mutating operation.

## Recipe contract

Each entry in `config/refactoring-recipes.json` has a stable ID, engine and
exact tool version, supported languages, explicit include and exclude paths,
path-class prohibitions, preconditions, expected invariants, behavior-change
classification, bounded match/file/line budgets, positive/negative/golden
fixtures, focused and full verification commands, and ownership, deprecation,
and compatibility metadata. Missing or unknown fields fail validation.

Generated, vendored, private, binary, build, and coordinator-state paths are
never eligible. Paths are repository-relative, normalized, and rejected when
they escape the checkout or traverse a symlink. A recipe must declare whether
it converges in one pass; otherwise a second application must produce no diff.

## Scan and plan

`refactorctl scan` and `refactorctl plan` are read-only. Scan reports matches
without writing source or catalog files. Plan binds the recipe digest, exact
base commit, normalized affected paths, match count, risk, budgets, and
required gates into a deterministic manifest and patch preview. It records no
raw source, command output, prompts, credentials, or machine-specific data in
coordination state.

Before any application, review the plan and verify that the claimed worktree is
clean, isolated, and based on the recorded revision. Refuse a plan when a
precondition, path boundary, digest, budget, or fixture does not match.

## Evidence and completion

Positive, negative, and golden fixtures must pass before a recipe is allowlisted.
The focused gates named by the recipe run first; then the complete applicable
repository gates run on the exact resulting tree. A failed gate preempts later
work. A successful scan or plan is not evidence that a transformation is
behavior-preserving; the declared invariants and verification results are the
evidence.

The second application must be a zero-diff convergence check unless the recipe
explicitly declares and tests a bounded multi-pass rule. Completion records the
immutable commit, recipe digest, plan digest, affected-path summary, gate
results, and limitations. Publication still follows `docs/DEVELOPMENT.md`.
