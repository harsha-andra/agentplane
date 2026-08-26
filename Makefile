.PHONY: up down logs test seed build test-unit test-integration test-worker test-console helm-lint

# Bring the whole stack up (Postgres, MongoDB, Redis, control plane, console). No Kubernetes
# cluster required - see docker-compose.yml and control-plane's orchestration.NoopJobLauncher.
up:
	docker compose up --build

# Same as `up`, but also seeds 5 tenants / ~80 runs on first boot (control-plane's `seed` profile).
seed:
	SPRING_PROFILES=local,seed docker compose up --build

down:
	docker compose down
	@echo "Named volumes (agentplane-postgres, agentplane-mongo) were kept - 'docker compose down -v' to also wipe them."

logs:
	docker compose logs -f

# Rebuild every image without starting anything (useful in CI before `up`/`seed`).
build:
	docker compose build

# Fast, infra-free suite for both halves of the system: the 71 control-plane unit tests (no
# Docker needed inside the JVM process itself - see control-plane/README.md's "Testing"
# section) plus the worker's 40 pytest tests (see worker/README.md).
test: test-unit test-worker

test-unit:
	cd control-plane && mvn -B clean verify

# Testcontainers-backed integration tests (real Postgres schema, real MongoDB aggregation
# pipeline) - excluded from the default `mvn verify` run; Docker required.
test-integration:
	cd control-plane && mvn -B verify -Pintegration

test-worker:
	cd worker && python3 -m venv .venv --upgrade-deps >/dev/null 2>&1 || true
	cd worker && . .venv/bin/activate && pip install -q -e ".[dev]" && python -m pytest

# Guarded: console/ is developed in parallel and may not always have node_modules installed in
# this checkout - skip cleanly rather than fail the whole `make test` run.
test-console:
	@if [ -d console ]; then \
		cd console && npm ci && npm run test -- --run; \
	else \
		echo "console/ not present - skipping"; \
	fi

helm-lint:
	@command -v helm >/dev/null 2>&1 && helm lint charts/agentplane || echo "helm not installed - skipping (see docs/ARCHITECTURE.md)"
