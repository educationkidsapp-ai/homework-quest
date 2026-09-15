env           = "qa"
project_id    = "homework-quest-qa"
region        = "me-central1"
github_repo   = "educationkidsapp-ai/homework-quest"
github_branch = "develop"
sql_tier      = "db-f1-micro"
cors_origins  = ["http://localhost:8081"]
# auth mail stays in the log until the owner provides RESEND_API_KEY and a verified mail_from (see deploy/README.md)
mail_provider = "log"
