# Actions

Every action is `<service>:<Verb>`. Anything not listed in an Allow statement is denied.

| Action | What it does | Risk |
| --- | --- | --- |
| `files:Read` | Read a file | low |
| `files:Search` | Full text search inside a file | low |
| `files:Write` | Create or update a file | medium |
| `files:Share` | Share a file with other users | medium |
| `files:Delete` | Delete a file | high |
| `secrets:Read` | Read a secret | high |
| `secrets:Rotate` | Rotate a secret and hand the new value to its consumers | high |
| `secrets:Write` | Write a secret value directly | critical |
| `iam:ListRoles` | List the roles | low |
| `iam:GetRole` | Read one role definition | low |
| `iam:GetPolicy` | Read the policy document | medium |
| `iam:PutPolicy` | Replace the policy document | critical |
| `iam:DeleteRole` | Delete a role | critical |
| `db:Query` | Run a read only query | medium |
| `db:Insert` | Write query results into a table | medium |
| `db:Snapshot` | Create a snapshot of a replica | high |
| `db:Dump` | Export a whole database | high |
| `db:Drop` | Drop a table | critical |
| `deploy:Status` | Read the deployment status | low |
| `deploy:Push` | Start a deployment | high |
| `deploy:Rollback` | Roll back a deployment | high |

Resources are paths: `files/<area>/<file>`, `secrets/<area>/<name>`, `iam/<object>`,
`db/<database>`, `deploy/<target>`.
