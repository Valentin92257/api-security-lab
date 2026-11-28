variable "REGISTRY" {
  default = "ghcr.io/valentin92257/api-security-lab"
}

variable "TAG" {
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
  tags = ["${REGISTRY}/${app}:${TAG}"]
}

target "lab-db" {
  inherits = ["_common"]
  target   = "lab-db"
  tags     = ["${REGISTRY}/lab-db:${TAG}"]
}
