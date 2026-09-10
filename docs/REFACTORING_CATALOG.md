# Initial refactoring catalog health

This report records the first measured catalog from AR-0018. It is a small
reviewed seed, not permission for a bulk campaign.

## Accepted recipe

`kotlin-explicit-boolean-wrapper` is an R1, one-pass recipe. It changes the
explicit `Boolean.valueOf(value)` expression to `value.toBoolean()`. The
positive fixture contains one match; the negative fixture contains no match;
the golden fixture is the reviewed expected output. The catalog excludes
generated, vendored, private, binary, build, and coordinator paths and limits
the run to 10 matches, 2 files, and 10 changed lines.

The application task requires a claimed AR-NNNN worktree and the existing
`refactorctl apply` boundary. Focused structural tests and the full repository
pytest gate are declared in the recipe. The semantic-drift test compares the
produced text with the golden fixture and verifies repeat discovery remains
stable; `refactorctl verify` supplies the zero-diff second-run check.

## Measurements and maintenance

The seed has one positive match in one file, one rejected negative case, and
one golden output. Review corrections: zero. Explained gate failures: zero
after the fixture shell metadata correction in the preceding structural-rule
change. Post-merge regression evidence is the merged PR's exact-tree checks;
this catalog adds no production transformation. A future recipe must report
the same match precision, changed files/lines, review corrections, focused and
full gate results, elapsed application time, and second-run drift.

Promote a proposal only after representative fixtures and an exact-tree
application pass with no unexplained correction or semantic drift. Demote or
retire it when false positives, fragile parsing, or review cost outweigh a
focused manual change. Roll back by reverting the recipe commit; never delete
evidence or broaden a path/budget to make a run pass.
