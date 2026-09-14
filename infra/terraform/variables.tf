variable "env" {
  description = "qa | prod — suffixes resource names and selects the Spring profile"
  type        = string
  validation {
    condition     = contains(["qa", "prod"], var.env)
    error_message = "env must be qa or prod"
  }
}
variable "project_id" { type = string }
variable "region" {
  type    = string
  default = "me-central1"
}
variable "github_repo" {
  description = "owner/name — the only repository allowed to deploy through Workload Identity Federation"
  type        = string
}
variable "github_branch" {
  description = "Branch whose workflows may deploy to this environment (develop → qa, main → prod)"
  type        = string
}
variable "admin_email" {
  type    = string
  default = "admin@quest.local"
}
variable "cors_origins" {
  description = "Extra CORS origins for the admin panel (local dev servers); the deployed panel is same-origin"
  type        = list(string)
  default     = []
}
variable "sql_tier" {
  type    = string
  default = "db-f1-micro"
}
variable "cloud_run_min_instances" {
  type    = number
  default = 0
}

# Secret *values* never live in tfvars or in the Terraform state: infra/bootstrap.sh and the deploy workflow add
# them to Secret Manager with `gcloud secrets versions add`. Optional secrets are wired into Cloud Run only once
# they exist — list them here after providing a value.
variable "optional_secrets" {
  description = "Optional secrets (ANTHROPIC_API_KEY, FIREBASE_CREDENTIALS) that have a version and should reach the server"
  type        = list(string)
  default     = []
  validation {
    condition     = alltrue([for s in var.optional_secrets : contains(["ANTHROPIC_API_KEY", "FIREBASE_CREDENTIALS"], s)])
    error_message = "optional_secrets may only contain ANTHROPIC_API_KEY and FIREBASE_CREDENTIALS"
  }
}
