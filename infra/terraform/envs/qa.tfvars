env           = "qa"
project_id    = "homework-quest-qa"
region        = "me-central1"
github_repo   = "educationkidsapp-ai/homework-quest"
github_branch = "develop"
sql_tier      = "db-f1-micro"
cors_origins  = ["http://localhost:8081"]
# auth mail stays in the log until the owner provides RESEND_API_KEY and a verified mail_from (see deploy/README.md)
mail_provider = "log"
# QA runs the owner's real school (one-school product, owner decision 2026-10-03), created through the wizard after
# the acceptance seed was wiped — so nothing seeds. This `false` is load-bearing: the `qa` Spring profile defaults
# SEED_SCHOOL to true, so dropping the line (or the env var in main.tf) would turn SchoolSeed back on.
# Features are verified against a local H2 server instead (e2e/README.md, e2e/local/parent-flows.sh).
seed_school = false
# Inert while seed_school = false: only SchoolSeed and AttemptSeed act on it (the server merely validates the name).
seed_profile = "acceptance"
# NEVER true on QA without the owner: SeedReset ignores seed_school and deletes every school that is not `default`
# entirely — that is now the owner's school, with its staff, children and lessons (docs/runbook.md).
seed_reset = false
