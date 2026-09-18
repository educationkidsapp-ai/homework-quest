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
