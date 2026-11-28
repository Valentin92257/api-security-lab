variable "REGISTRY" {
  default = "api-security-lab"
}

variable "TAGS" {
  default = "dev"
}

variable "SKIP_TESTS" {
  default = "false"
}

variable "REVISION" {
  default = ""
}

group "default" {
  targets = ["java", "lab-db"]
}

target "_common" {
  context = "."
  labels = {
    "org.opencontainers.image.source"   = "https://github.com/Valentin92257/api-security-lab"
    "org.opencontainers.image.revision" = REVISION
  }
}

target "java" {
  name     = app
  matrix   = { app = ["shop-api", "lab-gateway", "lab-detector", "lab-control"] }
  inherits = ["_common"]
  target   = "java-app"
  args = {
    APP        = app
    SKIP_TESTS = SKIP_TESTS
  }
  tags = [for tag in split(",", TAGS) : "${REGISTRY}/${app}:${tag}"]
}

target "lab-db" {
  inherits = ["_common"]
  target   = "lab-db"
  tags     = [for tag in split(",", TAGS) : "${REGISTRY}/lab-db:${tag}"]
}
