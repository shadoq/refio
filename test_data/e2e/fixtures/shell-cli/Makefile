.PHONY: build deploy lint

build:
	bash bin/deploy.sh --dry-run

deploy:
	bash bin/deploy.sh

lint:
	bash -n bin/deploy.sh lib/common.sh
