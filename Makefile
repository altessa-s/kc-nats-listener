SHELL := bash
.ONESHELL:
.SHELLFLAGS := -eu -o pipefail -c
.DELETE_ON_ERROR:
MAKEFLAGS += --warn-undefined-variables
MAKEFLAGS += --no-builtin-rules

# --- Project ---
PROJECT       := kc-nats-listener
GRADLE        ?= ./gradlew
BUILD_DIR     := build
LIBS_DIR      := $(BUILD_DIR)/libs

# --- Git ---
VERSION ?= $(shell git describe --tags --always --dirty 2>/dev/null || echo "dev")
COMMIT  ?= $(shell git rev-parse --short HEAD 2>/dev/null || echo "unknown")

.DEFAULT_GOAL := help

##@ Development

.PHONY: build
build: ## Build the shadow JAR
	$(GRADLE) shadowJar

.PHONY: test
test: ## Run tests
	$(GRADLE) test

.PHONY: clean
clean: ## Remove build artifacts
	$(GRADLE) clean

.PHONY: check
check: ## Run all checks (test + build)
	$(GRADLE) clean test shadowJar

##@ Utilities

.PHONY: deps
deps: ## Show project dependencies
	$(GRADLE) dependencies

.PHONY: jar-contents
jar-contents: build ## List contents of the shadow JAR
	@jar tf $(LIBS_DIR)/$(PROJECT)-*.jar | head -50

.PHONY: version
version: ## Show project version info
	@echo "Project:  $(PROJECT)"
	@echo "Version:  $(VERSION)"
	@echo "Commit:   $(COMMIT)"
	@echo "JAR:      $(LIBS_DIR)/$(PROJECT)-*.jar"

##@ Help

.PHONY: help
help: ## Show this help
	@awk 'BEGIN {FS = ":.*##"; printf "Usage:\n  make \033[36m<target>\033[0m\n"} \
		/^[a-zA-Z_-]+:.*?## / {printf "  \033[36m%-20s\033[0m %s\n", $$1, $$2} \
		/^##@/ {printf "\n\033[1m%s\033[0m\n", substr($$0, 5)}' $(MAKEFILE_LIST)
