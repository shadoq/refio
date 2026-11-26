# IAM review: least-privilege policy for October 2026

flagged_principal = svc-report
flagged_reason = On 2026-10-23 between 02:14 and 02:57 svc-report ran a night-time burst of 23 requests for actions it had never used in 30 days (db:Query on db/payroll and db/customers, db:Dump, files:Delete, secrets:Read, iam:PutPolicy, deploy:Push), outside its usual resource db/reporting, and all of them came from a new source address (10.42.9.77, not its usual 10.8.4.12).
statements = 9

## Why svc-report is flagged

Three signals line up for this one credential and for no other:

* **Time.** The reporting job runs at 06:30 every morning. The burst ran from 02:14 to 02:57
  on 23 October, between the nightly backup and the first human activity of the day.
* **Actions and resources.** In the 22 days before the burst the account only used `db:Query`
  and `db:Insert` on `db/reporting`. The burst asked for `db:Query` on `db/payroll` and
  `db/customers`, a `db:Dump` of the production replica, `files:Read` on the salary bands and a
  `files:Delete` of the 2024 expenses file, then tried `secrets:Read` on three production
  secrets, `iam:PutPolicy` and `deploy:Push`.
* **Source.** Every legitimate request of the account comes from 10.8.4.12; the burst came
  from 10.42.9.77, an address that appears nowhere else in the logs.

The broad temporary statement `report-export-temporary` (`db:*` and `files:*` on `*`) let the
first group through; the secrets, IAM and deploy requests were denied only because no
statement covered them. `iam:PutPolicy` on the next attempt would have changed that.

Other things that look odd but are fine: `svc-backup` runs at 02:10 every night with the same
requests from the same address; `svc-deploy` pushed a hotfix at 03:40 on 12 October from its
usual address; `gita` moved to the new office subnet on 20 October and kept the same working
hours and the same actions.

## The new policy

The policy was rebuilt from the logs, one `Allow` statement per principal, exact names and no
`Deny` statements. `svc-report` has no statement: the credential is treated as compromised and
is switched off until it is rotated and the reporting job gets a statement for
`db:Query` and `db:Insert` on `db/reporting` only.

| Principal | Statement | Keeps |
| --- | --- | --- |
| svc-backup | backup-reads-every-file | `files:Read` on `files/*` (it reads every file nightly) |
| svc-deploy | deploy-pipeline | `deploy:Push`, `deploy:Status` on `deploy/api`, `deploy/worker` |
| dana | support-reads-runbooks | `files:Read`, `files:Search` on `files/runbooks/*` |
| eric | finance-works-on-finance-files | `files:Read`, `files:Share`, `files:Write` on `files/finance/*` |
| fiona | oncall-rotates-oncall-secrets | `secrets:Read`, `secrets:Rotate` on the two on-call secrets |
| hugo | dba-works-on-the-replica | `db:Query`, `db:Snapshot` on `db/prod-replica` |
| ivy | analyst-queries-the-warehouse | `db:Query` on `db/warehouse` |
| gita | engineering-owns-eng-docs | `files:Read`, `files:Share`, `files:Write` on `files/eng/*` |
| kunal | platform-reads-the-iam-roles | `iam:GetRole`, `iam:ListRoles` on the roles |

What was removed: the two `*:*` statements, `files:*` and `secrets:Read` for everybody in
`staff`, `db:*` for the DBA and analyst groups, `iam:*` and `deploy:*` for the platform group,
and the temporary statement of `svc-report`. Nobody had used `iam:GetPolicy`, `iam:DeleteRole`,
`secrets:Write`, `db:Drop` or `deploy:Rollback` in 30 days, so nobody keeps them.

## Follow-up

1. Revoke the key of `svc-report`, find out how 10.42.9.77 reached the API, and restore
   `files/finance/expenses-2024.xlsx` from the backup of 23 October (02:10).
2. Review whether `svc-backup` needs to read the salary bands and the expenses file.
3. Add an alert for a principal that uses an action for the first time, and for a new source
   address on a service account.
