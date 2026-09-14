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
  description = "Admin-panel origins; the Firebase Hosting site is added automatically"
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

# Secret *values* never live in tfvars. They come from GitHub Actions secrets (TF_VAR_...) or your shell at bootstrap.
variable "deepseek_api_key" {
  type      = string
  sensitive = true
  default   = ""
}
variable "anthropic_api_key" {
  type      = string
  sensitive = true
  default   = ""
}
variable "admin_password" {
  type      = string
  sensitive = true
  default   = ""
}
variable "firebase_credentials_json" {
  description = "Service-account JSON the server uses to verify parents' Firebase ID tokens (empty keeps the previous version)"
  type        = string
  sensitive   = true
  default     = ""
}
