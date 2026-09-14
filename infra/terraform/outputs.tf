# deterministic form (same value PUBLIC_URL and the admin bundle use); .uri would return the legacy random-suffix host
output "cloud_run_url" { value = local.api_url }
output "artifact_registry" { value = "${var.region}-docker.pkg.dev/${var.project_id}/homework-quest" }
output "image" { value = "${var.region}-docker.pkg.dev/${var.project_id}/homework-quest/${local.service}" }
output "wif_provider" { value = google_iam_workload_identity_pool_provider.github.name }
output "deployer_service_account" { value = google_service_account.deployer.email }
output "runtime_service_account" { value = google_service_account.runtime.email }
output "sql_connection_name" { value = google_sql_database_instance.db.connection_name }
output "bucket" { value = google_storage_bucket.files.name }
output "admin_url" { value = "${local.api_url}/panel/" }
