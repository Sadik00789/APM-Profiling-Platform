.PHONY: all start stop build run-collector run-agent ui-dev ui-build test clean

all: build

start:
	docker compose up -d

stop:
	docker compose down

build:
	./gradlew build

run-collector:
	./gradlew :apm-collector-core:bootRun

run-agent:
	./gradlew :apm-synthetic-agent:bootRun

ui-install:
	cd apm-web-ui && npm install

ui-dev:
	cd apm-web-ui && npm run dev

ui-build:
	cd apm-web-ui && npm run build

test:
	./gradlew test

clean:
	./gradlew clean
	docker compose down -v
