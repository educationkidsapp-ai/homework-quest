locals {
  name        = "homework-quest-${var.env}"
  service     = "homework-quest-api"
  profile     = var.env == "prod" ? "prod" : "qa"
  hosting_url = "https://${var.project_id}.web.app"
  apis = [
    "run.googleapis.com", "sqladmin.googleapis.com", "storage.googleapis.com", "secretmanager.googleapis.com",
    "artifactregistry.googleapis.com", "iam.googleapis.com", "iamcredentials.googleapis.com", "sts.googleapis.com",
    "cloudresourcemanager.googleapis.com", "firebase.googleapis.com", "firebasehosting.googleapis.com", "identitytoolkit.googleapis.com",
    "firebaseappdistribution.googleapis.com",
  ]
}

resource "google_project_service" "apis" {
  for_each           = toset(local.apis)
  service            = each.key
  disable_on_destroy = false
}

# ---------------------------------------------------------------- images
resource "google_artifact_registry_repository" "images" {
  repository_id = "homework-quest"
  format        = "DOCKER"
  location      = var.region
  description   = "Server images; QA builds are promoted to production by digest, never rebuilt"
  cleanup_policies {
    id     = "keep-recent"
    action = "KEEP"
    most_recent_versions { keep_count = 30 }
  }
  depends_on = [google_project_service.apis]
}

# ---------------------------------------------------------------- database
resource "random_password" "db" {
  length  = 32
  special = false
}

resource "google_sql_database_instance" "db" {
  name             = "${local.name}-db"
  database_version = "POSTGRES_16"
  region           = var.region
  settings {
    tier              = var.sql_tier
    edition           = "ENTERPRISE" # shared-core tiers (db-f1-micro) only exist in the Enterprise edition
    availability_type = var.env == "prod" ? "REGIONAL" : "ZONAL"
    disk_autoresize   = true
    backup_configuration {
      enabled                        = true
      point_in_time_recovery_enabled = var.env == "prod"
      start_time                     = "02:00"
    }
    ip_configuration {
      ipv4_enabled = true # reached only through the Cloud SQL connector (IAM + TLS); no authorized networks
    }
    maintenance_window {
      day  = 7
      hour = 3
    }
  }
  deletion_protection = var.env == "prod"
  depends_on          = [google_project_service.apis]
}

resource "google_sql_database" "quest" {
  name     = "quest"
  instance = google_sql_database_instance.db.name
}

resource "google_sql_user" "quest" {
  name     = "quest"
  instance = google_sql_database_instance.db.name
  password = random_password.db.result
}

# ---------------------------------------------------------------- files
resource "google_storage_bucket" "files" {
  name                        = "${var.project_id}-files"
  location                    = var.region
  uniform_bucket_level_access = true
  force_destroy               = var.env != "prod"
  lifecycle_rule {
    # uploaded slides live 24 hours; page images and children's media (other prefixes) stay
    condition {
      age            = 1
      matches_prefix = ["uploads/"]
    }
    action {
      type = "Delete"
    }
  }
  depends_on = [google_project_service.apis]
}

# ---------------------------------------------------------------- secrets
# Terraform owns the secret *containers* and the two values it generates. User-supplied values (LLM keys, admin
# password, optional Firebase JSON) are added as versions by infra/bootstrap.sh and the deploy workflow with
# `gcloud secrets versions add` (only when the value changed), so they never sit in the Terraform state.
resource "random_password" "jwt" {
  length  = 48
  special = false
}

locals {
  generated_secrets = {
    DB_PASSWORD      = random_password.db.result
    ADMIN_JWT_SECRET = random_password.jwt.result
  }
  required_secrets = ["DB_PASSWORD", "ADMIN_JWT_SECRET", "DEEPSEEK_API_KEY", "ADMIN_PASSWORD"]
  optional_secrets = ["ANTHROPIC_API_KEY", "FIREBASE_CREDENTIALS"]
  # wired into Cloud Run: the required ones always, an optional one once its value has been provided (var.optional_secrets)
  runtime_secrets = concat(local.required_secrets, var.optional_secrets)
}

resource "google_secret_manager_secret" "s" {
  for_each  = toset(concat(local.required_secrets, local.optional_secrets))
  secret_id = each.key
  replication {
    auto {}
  }
  depends_on = [google_project_service.apis]
}

resource "google_secret_manager_secret_version" "generated" {
  for_each    = local.generated_secrets
  secret      = google_secret_manager_secret.s[each.key].id
  secret_data = each.value
}

# ---------------------------------------------------------------- runtime service account
resource "google_service_account" "runtime" {
  account_id   = "${local.service}-sa"
  display_name = "Homework Quest API runtime (${var.env})"
}

resource "google_project_iam_member" "runtime_sql" {
  project = var.project_id
  role    = "roles/cloudsql.client"
  member  = "serviceAccount:${google_service_account.runtime.email}"
}

resource "google_storage_bucket_iam_member" "runtime_bucket" {
  bucket = google_storage_bucket.files.name
  role   = "roles/storage.objectAdmin"
  member = "serviceAccount:${google_service_account.runtime.email}"
}

resource "google_secret_manager_secret_iam_member" "runtime_secrets" {
  for_each  = google_secret_manager_secret.s
  secret_id = each.value.id
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.runtime.email}"
}

# ---------------------------------------------------------------- Cloud Run
# Terraform owns the service and its configuration; the deploy workflow only swaps the image and shifts traffic.
resource "google_cloud_run_v2_service" "api" {
  name                = local.service
  location            = var.region
  ingress             = "INGRESS_TRAFFIC_ALL"
  deletion_protection = var.env == "prod"

  template {
    service_account = google_service_account.runtime.email
    timeout         = "600s"
    scaling {
      min_instance_count = var.cloud_run_min_instances
      max_instance_count = var.env == "prod" ? 5 : 2
    }
    max_instance_request_concurrency = 20
    volumes {
      name = "cloudsql"
      cloud_sql_instance {
        instances = [google_sql_database_instance.db.connection_name]
      }
    }
    containers {
      # Placeholder that answers 200 on every path (so the startup probe passes); the deploy workflow swaps in the real image
      # and Terraform ignores that field afterwards.
      image = "us-docker.pkg.dev/cloudrun/container/hello"
      resources {
        limits            = { cpu = "2", memory = "2Gi" }
        cpu_idle          = true
        startup_cpu_boost = true
      }
      volume_mounts {
        name       = "cloudsql"
        mount_path = "/cloudsql"
      }
      startup_probe {
        http_get {
          path = "/health"
        }
        initial_delay_seconds = 20
        period_seconds        = 10
        failure_threshold     = 12
      }
      env {
        name  = "SPRING_PROFILES_ACTIVE"
        value = local.profile
      }
      env {
        # Firebase Admin verifies parents' ID tokens with the runtime service account (no key file); it needs the project id.
        name  = "GOOGLE_CLOUD_PROJECT"
        value = var.project_id
      }
      env {
        name  = "CLOUD_SQL_INSTANCE"
        value = google_sql_database_instance.db.connection_name
      }
      env {
        name  = "DB_NAME"
        value = google_sql_database.quest.name
      }
      env {
        name  = "DB_USER"
        value = google_sql_user.quest.name
      }
      env {
        name  = "STORAGE_KIND"
        value = "gcs"
      }
      env {
        name  = "GCS_BUCKET"
        value = google_storage_bucket.files.name
      }
      env {
        name  = "LLM_PROVIDER"
        value = "deepseek"
      }
      env {
        name  = "ADMIN_EMAIL"
        value = var.admin_email
      }
      env {
        name  = "CORS_ORIGINS"
        value = join(",", concat([local.hosting_url], var.cors_origins))
      }
      env {
        name  = "PUBLIC_URL"
        value = "https://${local.service}-${data.google_project.this.number}.${var.region}.run.app"
      }
      dynamic "env" {
        for_each = toset(local.runtime_secrets)
        content {
          name = env.key
          value_source {
            secret_key_ref {
              secret  = google_secret_manager_secret.s[env.key].secret_id
              version = "latest"
            }
          }
        }
      }
    }
  }

  lifecycle {
    # the deploy workflows own the running image and the traffic split
    ignore_changes = [template[0].containers[0].image, traffic, client, client_version, template[0].labels, template[0].annotations, scaling]
  }
  depends_on = [google_secret_manager_secret_version.generated, google_secret_manager_secret_iam_member.runtime_secrets]
}

data "google_project" "this" {}

resource "google_cloud_run_v2_service_iam_member" "public" {
  name     = google_cloud_run_v2_service.api.name
  location = var.region
  role     = "roles/run.invoker"
  member   = "allUsers"
}

# ---------------------------------------------------------------- GitHub Actions → GCP (Workload Identity Federation, no keys)
resource "google_iam_workload_identity_pool" "github" {
  workload_identity_pool_id = "github-${var.env}"
  display_name              = "GitHub Actions (${var.env})"
  depends_on                = [google_project_service.apis]
}

resource "google_iam_workload_identity_pool_provider" "github" {
  workload_identity_pool_id          = google_iam_workload_identity_pool.github.workload_identity_pool_id
  workload_identity_pool_provider_id = "github"
  display_name                       = "GitHub OIDC"
  attribute_mapping = {
    "google.subject"       = "assertion.sub"
    "attribute.repository" = "assertion.repository"
    "attribute.ref"        = "assertion.ref"
  }
  # only this repo, and only workflows running on this environment's branch (or a GitHub environment of that name)
  # QA additionally lets workflows on main read its registry, so production promotes the tested image by digest.
  attribute_condition = "assertion.repository == '${var.github_repo}' && (assertion.ref == 'refs/heads/${var.github_branch}' || assertion.environment == '${var.env == "prod" ? "production" : "qa"}'${var.env == "qa" ? " || assertion.ref == 'refs/heads/main'" : ""})"
  oidc {
    issuer_uri = "https://token.actions.githubusercontent.com"
  }
}

resource "google_service_account" "deployer" {
  account_id   = "github-deployer-${var.env}"
  display_name = "GitHub Actions deployer (${var.env})"
}

resource "google_service_account_iam_member" "deployer_wif" {
  service_account_id = google_service_account.deployer.name
  role               = "roles/iam.workloadIdentityUser"
  member             = "principalSet://iam.googleapis.com/${google_iam_workload_identity_pool.github.name}/attribute.repository/${var.github_repo}"
}

resource "google_project_iam_member" "deployer" {
  for_each = toset([
    "roles/run.admin", "roles/artifactregistry.writer", "roles/iam.serviceAccountUser",
    "roles/secretmanager.admin", "roles/cloudsql.client", "roles/firebasehosting.admin", "roles/storage.objectViewer",
    "roles/firebaseappdistro.admin", # QA APK → App Distribution
  ])
  project = var.project_id
  role    = each.key
  member  = "serviceAccount:${google_service_account.deployer.email}"
}

# Terraform apply from CI needs to manage the same resources it created here.
resource "google_project_iam_member" "deployer_editor" {
  project = var.project_id
  role    = "roles/editor"
  member  = "serviceAccount:${google_service_account.deployer.email}"
}
