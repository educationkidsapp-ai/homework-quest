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
# ---------------------------------------------------------------- auth email (invites, password resets)
variable "mail_provider" {
  description = "log | resend — `log` writes the invite/reset link to the Cloud Run log instead of sending it (no key needed); switch to `resend` once RESEND_API_KEY has a version and mail_from is a verified sender"
  type        = string
  default     = "log"
  validation {
    condition     = contains(["log", "resend"], var.mail_provider)
    error_message = "mail_provider must be log or resend"
  }
}
variable "mail_from" {
  description = "Sender address for auth mail. The default is a deliberate placeholder on the RFC 2606 `.invalid` TLD — it is never used while mail_provider = \"log\"; replace it with an address on a Resend-verified domain before switching to `resend`"
  type        = string
  default     = "no-reply@homework-quest.invalid"
}
# The product name is no longer an env var: since P2.1 it lives in the `platform_settings` table and is edited in the
# dashboard under Platform settings (`PUT /admin/platform-settings`).

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
  description = "Optional secrets (ANTHROPIC_API_KEY, FIREBASE_CREDENTIALS, RESEND_API_KEY) that have a version and should reach the server"
  type        = list(string)
  default     = []
  validation {
    condition     = alltrue([for s in var.optional_secrets : contains(["ANTHROPIC_API_KEY", "FIREBASE_CREDENTIALS", "RESEND_API_KEY"], s)])
    error_message = "optional_secrets may only contain ANTHROPIC_API_KEY, FIREBASE_CREDENTIALS and RESEND_API_KEY"
  }
}
