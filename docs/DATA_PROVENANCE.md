# Multilingual Ground-Truth Data Provenance

`backend/src/main/resources/evaluation/ground_truth.csv` is a small synthetic/de-identified evaluation corpus created for this academic capstone. It contains English, Hindi and Marathi complaint examples with expected categories.

- Version: v1.0
- Source type: synthetic academic test data; no citizen PII
- Fields: id, language, expected_category, text
- Intended use: repeatable classification/routing evaluation
- Permissions: generated for the project; no external personal records are used
- Reproducibility: the same CSV is bundled with the project and evaluated by `evaluation/evaluate.py` and the Admin Evaluation page.

Do not present this small corpus as representative of real municipal population prevalence. Use it only for reproducible prototype evaluation.
