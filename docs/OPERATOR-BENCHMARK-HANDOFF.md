# Operator Benchmark Handoff (portable rule)

This file preserves the *durable* rule recovered from the desktop-parrot checkout
without adopting that machine's absolute-path contract. It is deliberately
repository-relative; the operator's benchmark location is deployment state, not a
repository path (see `REPRODUCIBILITY.md` in `fr3kchy/research`).

## Rule

1. After each successful **new APK or firmware package**, update the operator's
   single latest-candidate benchmark selection and refresh the operator benchmark
   notes.
2. Verify hashes for every published candidate.
3. Record explicitly which changes are **unbuilt** (present in source but not in
   the selected candidate).
4. Preserve operator test results as evidence; never overwrite reported results.
5. Continue to preserve existing dirty working trees.
6. **Never claim physical readiness from a build alone.** A green Gradle build or
   a produced APK proves compilation, not device acceptance.

## Non-portable contract that is *not* adopted

The recovered file `/home/parrot/FR3K-BENCHMARK/AGENT-HANDOFF.md` referenced by
the recovery branch is a machine-local path. Do not encode an absolute host path
as repository governance. Resolve the benchmark location at run time through the
operator's environment / research `REPRODUCIBILITY.md`.
