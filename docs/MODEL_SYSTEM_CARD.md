# SamadhanPoint Model / System Card

## Purpose
Assist municipal grievance intake with multilingual language detection, category suggestion, translation, priority signals and duplicate/human-review signals.

## Supported languages
English, Hindi, Marathi.

## AI provider
OpenAI Responses API when `OPENAI_API_KEY` is configured. The configured model is controlled by `OPENAI_MODEL` and defaults to `gpt-6-luna` in this academic build.

## Deterministic safeguards
Java remains authoritative for authentication/RBAC, complaint-location scope, ward resolution, department routing, worker assignment, SLA policy and final workflow transitions. Strong road/street-light/water rule guards prevent an AI guess from overriding an explicit server-side routing signal.

## Human escalation
Low confidence, ambiguous cases and strong duplicate similarity can be flagged for human review. Citizen appeals are reviewed by an Admin or Department Head.

## Privacy
Use synthetic/de-identified complaints for evaluation. Do not commit API keys, passwords or personal data. Production deployment should apply appropriate retention and access controls.

## Known limitations
OpenAI availability depends on a valid API key/network. If unavailable, the deterministic local triage fallback remains operational. Formal performance claims must be generated from the included ground-truth evaluation set rather than invented.
