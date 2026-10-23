## Change

Describe the user-visible problem and resulting behavior.

## Validation

Include Android build/tests/lint, applicable automation checks and the Actions run URL.

## Performance

Link the Actions **Virtual performance** comment or artifact with baseline, previous main and
current PR results. The required check runs the same virtual Android workload on both revisions.
No physical-phone tests are required. Do not label software counts/CPU times as battery endurance.

| Measurement | Baseline budget | Previous main (SHA) | Current PR (SHA) |
| --- | --- | --- | --- |
| Screen on/off/check at 1/5/20/30/60/300-second GPS intervals | | | |
| Hidden draw attempts / tile reads / pending recorder work | 0 | | |
| Recording notification posts / saved fixes / GPS requests | see report | | |
| Native Canvas CPU median, 1k and 10k route points | see report | | |

Comparison verdict and Actions evidence:

- [ ] Automated virtual comparison passes for the current head.
- [ ] Current counts meet baseline/previous budgets; CPU timing meets its stated noise margin.

**Enable native auto-merge after diff review, while Actions are still running.**
The required `checks` job fails if the virtual comparison fails or is incomplete;
GitHub waits for passing checks on the current source before merging. Link the report when available.
