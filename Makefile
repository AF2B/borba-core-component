.PHONY: run stag prod test test-stag test-prod coverage clean deps build \
        docker-build docker-push docker-run docker-up docker-down \
        fmt-check fmt-fix init lint help

SERVICE_NAME   = borba-core-component
RUN            = run
STAG_PROFILE   = stag
PROD_PROFILE   = prod
TEST_PROFILE   = test

REGISTRY       = ghcr.io/af2b
IMAGE          = $(REGISTRY)/$(SERVICE_NAME)
VERSION        = $(shell git describe --tags --always --dirty 2>/dev/null || echo "dev")
SHA            = $(shell git rev-parse --short HEAD 2>/dev/null || echo "unknown")
PORT           = 8080
PROFILE        = prod

run:
	@echo "▶ Running with default (stag) profile..."
	@clojure -M:run

stag:
	@echo "▶ Running with staging profile..."
	@clojure -M:$(RUN) $(STAG_PROFILE)

prod:
	@echo "▶ Running with production profile..."
	@clojure -M:$(RUN) $(PROD_PROFILE)

test:
	@echo "▶ Running tests with test profile..."
	@clojure -M:test -m kaocha.runner
	@echo "✅ Tests passed"

test-stag:
	@echo "▶ Running tests with staging config..."
	@clojure -M:test -m kaocha.runner --profile stag
	@echo "✅ Tests passed (stag)"

test-prod:
	@echo "▶ Running tests with production config..."
	@clojure -M:test -m kaocha.runner --profile prod
	@echo "✅ Tests passed (prod)"

coverage:
	@echo "▶ Running tests with coverage..."
	@clojure -M:test -m kaocha.runner --plugin kaocha.plugin/cloverage --cov-output target/coverage
	@echo "✅ Coverage report: target/coverage/index.html"

fmt-check:
	@echo "▶ Checking formatting (src/ test/)..."
	@clojure -M:fmt-check
	@echo "✅ Formatting OK"

fmt-fix:
	@echo "▶ Fixing formatting (src/ test/)..."
	@clojure -M:fmt-fix
	@echo "✅ Formatting fixed"

lint:
	@echo "▶ Linting (src/)..."
	@clojure -M:lint
	@echo "✅ Lint OK"

build:
	@echo "▶ Building library JAR..."
	@APP_VERSION=$(VERSION) clojure -T:build jar
	@echo "✅ Built: target/$(SERVICE_NAME)-$(VERSION).jar"

clean:
	@echo "▶ Cleaning build artifacts..."
	@rm -rf .cpcache target
	@echo "✅ Clean"

deps:
	@echo "▶ Resolving dependencies..."
	@clojure -P
	@clojure -P -M:test
	@echo "✅ Dependencies resolved"

docker-build:
	@echo "▶ Building Docker image..."
	@docker build \
		--build-arg APP_VERSION=$(VERSION) \
		--build-arg PORT=$(PORT) \
		--build-arg PROFILE=$(PROFILE) \
		-t $(IMAGE):$(SHA) \
		-t $(IMAGE):$(VERSION) \
		-t $(IMAGE):latest \
		.
	@echo "✅ Image built: $(IMAGE):latest"

docker-push:
	@echo "▶ Pushing Docker image..."
	@docker push $(IMAGE):$(SHA)
	@docker push $(IMAGE):$(VERSION)
	@docker push $(IMAGE):latest
	@echo "✅ Image pushed: $(IMAGE)"

docker-run:
	@docker run --rm \
		-p $(PORT):$(PORT) \
		-e PROFILE=$(PROFILE) \
		$(IMAGE):latest

docker-up:
	@echo "▶ Starting infrastructure (PostgreSQL, Redis, Kafka, Schema-Registry)..."
	@docker compose up -d
	@echo "✅ Infrastructure running"

docker-down:
	@echo "▶ Stopping infrastructure..."
	@docker compose down -v
	@echo "✅ Infrastructure stopped"

# ─────────────────────────────────────────────────────────────────────────────
# Scaffolding — Generate a new microservice from this boilerplate
# ─────────────────────────────────────────────────────────────────────────────
#
# Usage:
#   make init NAME=exemplo-service
#
# This will:
#   1. Copy the boilerplate to ../exemplo-service (excluding component folders)
#   2. Rename all directories from be_boilerplate → exemplo_service
#   3. Replace all namespace references (be-boilerplate → exemplo-service)
#   4. Maintain the com/borba/<service> convention
#
# The borba-*-component folders are NOT copied — they are separate
# repositories to be added as deps once pushed to git.
# ─────────────────────────────────────────────────────────────────────────────

init:
ifndef NAME
	$(error ❌ NAME is required. Usage: make init NAME=payment-service)
endif
	@echo "▶ Scaffolding new service: $(NAME)..."
	$(eval UNDERSCORE := $(subst -,_,$(NAME)))
	$(eval SRC_UNDERSCORE := be_boilerplate)
	$(eval SRC_DASH := be-boilerplate)
	$(eval TARGET_DIR := ../$(NAME))
	@if [ -d "$(TARGET_DIR)" ]; then \
		echo "❌ Directory $(TARGET_DIR) already exists. Aborting."; \
		exit 1; \
	fi
	@mkdir -p "$(TARGET_DIR)"
	@# Copy everything EXCEPT borba-*-component folders, .git, .cpcache and target
	@find . -mindepth 1 -maxdepth 1 \
		! -name '.git' \
		! -name '.cpcache' \
		! -name 'target' \
		! -name 'borba-*-component' \
		-exec cp -r {} "$(TARGET_DIR)/" \;
	@# Remove any borba-*-component dirs that may have slipped through (glob safety)
	@find "$(TARGET_DIR)" -maxdepth 1 -type d -name 'borba-*-component' -exec rm -rf {} + 2>/dev/null || true
	@echo "  ├─ Copied boilerplate to $(TARGET_DIR)"
	@# Rename source directories
	@if [ -d "$(TARGET_DIR)/src/com/borba/$(SRC_UNDERSCORE)" ]; then \
		mv "$(TARGET_DIR)/src/com/borba/$(SRC_UNDERSCORE)" \
		   "$(TARGET_DIR)/src/com/borba/$(UNDERSCORE)"; \
	fi
	@if [ -d "$(TARGET_DIR)/test/com/borba/$(SRC_UNDERSCORE)" ]; then \
		mv "$(TARGET_DIR)/test/com/borba/$(SRC_UNDERSCORE)" \
		   "$(TARGET_DIR)/test/com/borba/$(UNDERSCORE)"; \
	fi
	@echo "  ├─ Renamed directories"
	@# Replace all references in files
	@find "$(TARGET_DIR)" -type f \
		\( -name "*.clj" -o -name "*.edn" -o -name "*.md" \
		   -o -name "Makefile" -o -name "Dockerfile" \
		   -o -name "*.yml" -o -name "*.yaml" -o -name "*.sh" \) \
		-exec sed -i'' \
			-e "s|$(SRC_UNDERSCORE)|$(UNDERSCORE)|g" \
			-e "s|$(SRC_DASH)|$(NAME)|g" \
			-e "s|SERVICE_NAME   = $(SRC_DASH)|SERVICE_NAME   = $(NAME)|g" \
			{} +
	@echo "  ├─ Updated all namespace references"
	@echo "  └─ Done"
	@echo ""
	@echo "✅ Service '$(NAME)' created at $(TARGET_DIR)"
	@echo ""
	@echo "   Next steps:"
	@echo "     cd $(TARGET_DIR)"
	@echo "     # Add borba-*-component git deps to deps.edn"
	@echo "     make deps"
	@echo "     make docker-up"
	@echo "     make run"

help:
	@echo ""
	@echo "╔══════════════════════════════════════════════════════════════════╗"
	@echo "║               $(SERVICE_NAME) — Makefile                       ║"
	@echo "╚══════════════════════════════════════════════════════════════════╝"
	@echo ""
	@echo "  Development:"
	@echo "    run            Run with default (stag) profile"
	@echo "    stag           Run with staging profile"
	@echo "    prod           Run with production profile"
	@echo ""
	@echo "  Testing:"
	@echo "    test           Run tests with test profile"
	@echo "    test-stag      Run tests with staging config"
	@echo "    test-prod      Run tests with production config"
	@echo "    coverage       Run tests + coverage report (target/coverage/)"
	@echo ""
	@echo "  Formatting:"
	@echo "    fmt-check      Check formatting without modifying files"
	@echo "    fmt-fix        Fix formatting in src/ and test/"
	@echo ""
	@echo "  Build:"
	@echo "    build          Compile uberjar → target/app.jar"
	@echo "    clean          Remove .cpcache & target"
	@echo "    deps           Resolve & cache all dependencies"
	@echo ""
	@echo "  Docker:"
	@echo "    docker-build   Build image (tags: sha, version, latest)"
	@echo "    docker-push    Push all tags to registry"
	@echo "    docker-run     Run container on PORT=$(PORT)"
	@echo "    docker-up      Start infra (Postgres, Redis, Kafka)"
	@echo "    docker-down    Stop & remove infra containers"
	@echo ""
	@echo "  Scaffolding:"
	@echo "    init NAME=x    Generate new microservice from boilerplate"
	@echo "                   Example: make init NAME=payment-service"
	@echo "                   Note: borba-*-component folders are excluded"
	@echo ""
	@echo "  Variables (override with make <target> VAR=value):"
	@echo "    REGISTRY=$(REGISTRY)"
	@echo "    PORT=$(PORT)"
	@echo "    PROFILE=$(PROFILE)"
	@echo ""
