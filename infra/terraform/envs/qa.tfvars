env           = "qa"
project_id    = "homework-quest-qa"
region        = "me-central1"
github_repo   = "educationkidsapp-ai/homework-quest"
github_branch = "develop"
sql_tier      = "db-f1-micro"
cors_origins  = ["http://localhost:8081"]
# auth mail stays in the log until the owner provides RESEND_API_KEY and a verified mail_from (see deploy/README.md)
mail_provider = "log"
# the one-school seed (30 classes / 40 teachers / 600 children) fills a fresh QA database; the `qa` Spring profile
# defaults it on anyway — this states the intent. Teacher password: optional secret SEED_STAFF_PASSWORD.
seed_school = true
# QA is the owner's acceptance environment (2026-09-19): two teachers, three sections, no seeded children.
# seed_reset = true wipes every school-scoped record and the two legacy schools on the next deploy — flip it back
# to false right after that deploy lands. The automated e2e suite needs `full`; while QA is on `acceptance`, the
# e2e job is switched off with the repository variable E2E_ON_QA=false.
seed_profile = "acceptance"
seed_reset   = false # the one-shot wipe ran on the deploy of #92/#93; leave off (the seed_resets ledger also blocks a repeat)
