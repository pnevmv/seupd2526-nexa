# Processed Datasets

Generated datasets live here so raw task files remain unchanged.

## Layout

- `documents/translated/`: materialized publication collection for indexing.
- `queries/translated/`: materialized translated claim files.
- `queries/expanded/`: materialized translated + expanded claim files.

## Current Paths

- Active collection path:
  `datasets/processed/documents/translated/collection_data_en_translated.json`
- Active translated query path:
  `datasets/processed/queries/translated/fr_train_en_translated.json`
- Expanded query target:
  `datasets/processed/queries/expanded/fr_train_en_translated_expanded.json`

Generate expanded queries with:

```bash
python3 code/scripts/expand_claims.py \
  --claims-file datasets/processed/queries/translated/fr_train_en_translated.json \
  --output-file datasets/processed/queries/expanded/fr_train_en_translated_expanded.json
```

After generation, set `topics` in `code/src/main/config/config.yml` to the expanded query target.

Note: `documents/translated/collection_data_en_translated.json` is currently initialized from the original collection file. Replace it with a true materialized translated collection if/when document translation is generated offline.
