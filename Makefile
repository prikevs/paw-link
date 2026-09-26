LABEL ?= unlabeled
DURATION ?= 10

.PHONY: setup compile upload monitor check collect train train-local import-open-data train-open-cat export-model classify pet-bridge test-pet-bridge clean

setup:
	./scripts/paw setup

compile:
	./scripts/paw compile

upload:
	./scripts/paw upload

monitor:
	./scripts/paw monitor

check:
	./scripts/paw check

collect:
	uv run python collector/collect.py --label "$(LABEL)" --duration "$(DURATION)"

train: train-local

train-local:
	uv run python collector/train.py

import-open-data:
	uv run python scripts/import_open_cat_dataset.py "$(ARCHIVE)"

train-open-cat:
	uv run python collector/train_open_cat.py
	uv run python scripts/export_model.py --model models/cat-behavior-rf.json

export-model:
	uv run python scripts/export_model.py --model models/cat-behavior-rf.json

classify:
	uv run python collector/classify.py

pet-bridge:
	uv run python scripts/monitor_predictions.py

test-pet-bridge:
	uv run python scripts/test_monitor_predictions.py -v

clean:
	./scripts/paw clean
