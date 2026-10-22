## Change

Describe the user-visible problem and resulting behavior.

## Validation

Include Android build/tests/lint and applicable automation checks.

## Performance

Every PR needs all three columns, in this description or a linked comment. Link the full
`battery_benchmark.py compare` report and raw evidence. Use immutable revisions and matched
device/OS/profile; include screen on/off/check and 1/5/20/30/60/300-second GPS intervals.
Mark unavailable measurements **unmeasured**; passing unit tests cannot substitute for energy trials.

| Measurement | Baseline (revision) | Previous main (revision) | Current PR (revision) |
| --- | --- | --- | --- |
| Discharge (mAh/h), per scenario | | | |
| Screen-off suspend / between-fix suspend (%) | | | |
| App wake locks / unrelated app wakeups | | | |
| Offscreen / idle map frames | | | |
| Visible frame p95 (ms) | | | |
| Fix coverage / p95 and maximum gaps / positional quality | | | |

Comparison verdict and evidence:

- [ ] Current results meet objectives and are no worse than baseline and previous main.
- [ ] No missing, stale, incompatible or inconclusive measurements.

**Keep auto-merge off if either box cannot be checked. Never reset a baseline to hide a regression.**
