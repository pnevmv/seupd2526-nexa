# Search Engines (SE) - NEXA Group - CheckThat! CLEF 2026 Task 1

This repository contains the code and papers produced in the Search Engines course, A.Y. 2025/2026, for the participation of the group NEXA at [CheckThat! 2026 Task 1](https://checkthat.gitlab.io/clef2026/task1/) at [CLEF 2026](https://clef2026.clef-initiative.eu/).

*Search Engines* is a course of the

* [Master Degree in Computer Engineering](https://degrees.dei.unipd.it/master-degrees/computer-engineering/) of the  [Department of Information Engineering](https://www.dei.unipd.it/en/), [University of Padua](https://www.unipd.it/en/), Italy.
* [Master Degree in Data Science](https://datascience.math.unipd.it/) of the  [Department of Mathematics "Tullio Levi-Civita"](https://www.math.unipd.it/en/), [University of Padua](https://www.unipd.it/en/), Italy.

*Search Engines* is part of the teaching activities of the [Intelligent Interactive Information Access (IIIA) Hub](http://iiia.dei.unipd.it/).

## Group members
- Paul Arlot - paullouisjean.arlot@studenti.unipd.it
- Andrea Di Tillo - andrea.ditillo@studenti.unipd.it
- Gaute Greiff Flagstad - gautegreiff.flaegstad@studenti.unipd.it
- Bita Khashechian - bita.khashechian@studenti.unipd.it
- Danil Smirnov - danil.smirnov@studenti.unipd.it
- Marco Tomaiuoli - marco.tomaiuoli@studenti.unipd.it

## Organisation of the repository

The repository is organised as follows:

* `code`: this folder contains the source code of the developed system. See dedicated section below for more details.
* `runs`: this folder contains the runs produced by the developed system.
* `results`: this folder contains the performance scores of the runs.
* `homework-1`: this folder contains the report describing the techniques applied and insights gained.
* `homework-2`: this folder contains the final paper submitted to CLEF.
* `slides`: this folder contains the slides used for presenting the conducted project.

## Quickstart — lexical search on English dev

No Python servers or GPU required. You need Java 21+ and Maven.

### 1. Download the data

```bash
pip install huggingface_hub
python3 - <<'EOF'
from huggingface_hub import hf_hub_download
import shutil, os

repo = "sschellhammer/CT26_Task1_SourceRetrievalForScientificWebClaims"
for f in ["collection_data.json", "en_dev.json"]:
    src = hf_hub_download(repo_id=repo, filename=f, repo_type="dataset")
    os.makedirs("datasets", exist_ok=True)
    shutil.copy(src, f"datasets/{f}")
EOF
```

### 2. Configure

Edit `code/src/main/config/config.yml` — change only these lines:

```yaml
collectionPath: datasets/collection_data.json
topics: datasets/en_dev.json
searchMode: lexical
enableQueryExpansion: false
runID: nexa-en-dev-quickstart
```

### 3. Build

```bash
cd code && mvn package -DskipTests && cd ..
```

### 4. Index

```bash
java -cp code/target/nexa-0.1-jar-with-dependencies.jar \
  it.unipd.dei.se.nexa.indexer.DirectoryIndexer
```

### 5. Search

```bash
java --add-modules jdk.incubator.vector \
  -cp code/target/nexa-0.1-jar-with-dependencies.jar \
  it.unipd.dei.se.nexa.searcher.Searcher
```

The run file is written to `runs/nexa-en-dev-quickstart.txt`.

### 6. Evaluate

```bash
python3 code/scripts/eval_run.py \
  runs/nexa-en-dev-quickstart.txt \
  --claims datasets/en_dev.json
```

Expected MRR@5: ~0.517.

---

## Python servers

The pipeline relies on up to four local Python servers. All servers share a
single virtual environment at `code/scripts/.venv`.

### Setup (one-time)

Create the virtual environment from the repository root.

macOS/Linux:

```bash
python3 -m venv code/scripts/.venv
```

Windows PowerShell:

```powershell
py -3 -m venv code\scripts\.venv
```

Install requirements for the servers you intend to run:

macOS/Linux:

```bash
# embedding server (required when embeddingsEnabled: true)
code/scripts/.venv/bin/pip install -r code/scripts/embedding/requirements.txt

# translation server (required when translateNonEnglish*: true)
code/scripts/.venv/bin/pip install -r code/scripts/translation/requirements.txt

# reranking server (required when reRank: true)
code/scripts/.venv/bin/pip install -r code/scripts/reranking/requirements.txt

# query expansion server (required when enableQueryExpansion: true)
code/scripts/.venv/bin/pip install -r code/scripts/query_expansion/requirements.txt
```

Windows PowerShell:

```powershell
code\scripts\.venv\Scripts\pip install -r code\scripts\embedding\requirements.txt
code\scripts\.venv\Scripts\pip install -r code\scripts\translation\requirements.txt
code\scripts\.venv\Scripts\pip install -r code\scripts\reranking\requirements.txt
code\scripts\.venv\Scripts\pip install -r code\scripts\query_expansion\requirements.txt
```

---

### Embedding server — port 8080

Required when `embeddingsEnabled: true` in `config.yml`. Runs BGE-M3 and Gemma
300M embedding models.

macOS/Linux:

```bash
bash code/scripts/embedding/run.sh
```

Windows PowerShell:

```powershell
code\scripts\.venv\Scripts\python.exe code\scripts\embedding\embedding_server.py
```

Health check:

```bash
curl http://127.0.0.1:8080/health
```

---

### Translation server — port 8081

Required when `translateNonEnglishPublicationsToEnglish: true` or
`translateNonEnglishClaimsToEnglish: true` in `config.yml`. Runs
`google/translategemma-4b-it` — a gated Hugging Face model requiring an access token.

Copy the example env file and set your token:

macOS/Linux:

```bash
cp code/scripts/translation/translategemma.env.example code/scripts/translation/translategemma.env
# edit translategemma.env and set HF_TOKEN=<your token>
```

Windows PowerShell:

```powershell
Copy-Item code\scripts\translation\translategemma.env.example code\scripts\translation\translategemma.env
notepad code\scripts\translation\translategemma.env
```

Start the server:

macOS/Linux:

```bash
bash code/scripts/translation/run.sh
```

Windows PowerShell:

```powershell
$env:HF_TOKEN = "your_token_here"
code\scripts\.venv\Scripts\python.exe code\scripts\translation\translation_server.py
```

Health check:

```bash
curl http://127.0.0.1:8081/health
```

---

### Reranking server — port 8082

Required when `reRank: true` in `config.yml`. Runs
`cross-encoder/ms-marco-MiniLM-L-6-v2`.

macOS/Linux:

```bash
bash code/scripts/reranking/run.sh
```

Windows PowerShell:

```powershell
code\scripts\.venv\Scripts\python.exe code\scripts\reranking\reranking_server.py
```

Health check:

```bash
curl http://127.0.0.1:8082/health
```

---

### Query expansion server — port 8001

Required when `enableQueryExpansion: true` in `config.yml`. Uses the Gemini API.

Set your API key in `code/scripts/query_expansion/queryexpansion.env`:

```
API_KEY=<your Gemini API key>
MODEL=gemini-1.5-flash
PORT=8001
```

Start the server:

macOS/Linux:

```bash
bash code/scripts/query_expansion/run.sh
```

Windows PowerShell:

```powershell
$env:API_KEY = "your_api_key_here"
code\scripts\.venv\Scripts\python.exe code\scripts\query_expansion\queryexpansion_server.py
```

Health check:

```bash
curl http://127.0.0.1:8001/health
```

To materialize expanded queries as a dataset file instead of expanding at
search time, run:

macOS/Linux:

```bash
python3 code/scripts/expand_claims.py \
  --claims-file datasets/processed/queries/translated/fr_train_en_translated.json \
  --output-file datasets/processed/queries/expanded/fr_train_en_translated_expanded.json
```

Windows PowerShell:

```powershell
code\scripts\.venv\Scripts\python.exe code\scripts\expand_claims.py `
  --claims-file datasets\processed\queries\translated\fr_train_en_translated.json `
  --output-file datasets\processed\queries\expanded\fr_train_en_translated_expanded.json
```

Then update `topics` in `config.yml` to point at the expanded file.

If the expansion server is unavailable during Java search, the searcher falls
back to the original query text and continues the run.

## Running the project pipeline

The pipeline is controlled by `code/src/main/config/config.yml`. The current
default configuration uses processed datasets under `datasets/processed/`:

```yaml
collectionPath: datasets/processed/documents/translated/collection_data_en_translated.json
topics: datasets/processed/queries/translated/fr_train_en_translated.json
expandedTopics: datasets/processed/queries/expanded/fr_train_en_translated_expanded.json
indexPath: experiment/index
runID: nexa-fr-train-translated-expanded
runPath: runs
```

Run all commands below from the repository root unless stated otherwise.

### 1. Build the Java package

```bash
cd code
mvn package
cd ..
```

This creates:

```text
code/target/nexa-0.1-jar-with-dependencies.jar
```

### 2. Start optional local services

Start the servers required by your `config.yml` settings (see the [Python servers](#python-servers) section above for setup and Windows instructions):

```bash
bash code/scripts/embedding/run.sh       # if embeddingsEnabled: true
bash code/scripts/translation/run.sh     # if translateNonEnglish*: true
bash code/scripts/reranking/run.sh       # if reRank: true
bash code/scripts/query_expansion/run.sh # if enableQueryExpansion: true
```

For the current processed-query setup, runtime claim translation is disabled
because `topics` already points to pre-translated claims.

### 3. Generate expanded query data

This step materializes expanded claims into `datasets/processed/queries/expanded/`.
It can be rerun safely; already-expanded records are skipped.

```bash
python3 code/scripts/expand_claims.py \
  --claims-file datasets/processed/queries/translated/fr_train_en_translated.json \
  --output-file datasets/processed/queries/expanded/fr_train_en_translated_expanded.json
```

Then set `topics` in `code/src/main/config/config.yml` to:

```yaml
topics: datasets/processed/queries/expanded/fr_train_en_translated_expanded.json
```

If you want runtime expansion instead of materialized expansion, keep `topics`
on the translated file and keep `enableQueryExpansion: true`.

### 4. Build the Lucene index

```bash
java -cp code/target/nexa-0.1-jar-with-dependencies.jar \
  it.unipd.dei.se.nexa.indexer.DirectoryIndexer
```

By default, this reads `collectionPath` and writes to `indexPath` from
`config.yml`. You can override both paths:

```bash
java -cp code/target/nexa-0.1-jar-with-dependencies.jar \
  it.unipd.dei.se.nexa.indexer.DirectoryIndexer \
  datasets/processed/documents/translated/collection_data_en_translated.json \
  experiment/index
```

### 5. Run search

```bash
java --add-modules jdk.incubator.vector \
  -cp code/target/nexa-0.1-jar-with-dependencies.jar \
  it.unipd.dei.se.nexa.searcher.Searcher
```

The run file is written to:

```text
runs/<runID>.txt
```

For the current config, that is:

```text
runs/nexa-fr-train-translated-expanded.txt
```

### 6. Evaluate a run

Use the same claims file used for search, so gold `pubkey` labels match the run:

```bash
python3 code/scripts/eval_run.py \
  runs/nexa-fr-train-translated-expanded.txt \
  --claims datasets/processed/queries/translated/fr_train_en_translated.json
```

If you searched with the expanded dataset, evaluate against that expanded file:

```bash
python3 code/scripts/eval_run.py \
  runs/nexa-fr-train-translated-expanded.txt \
  --claims datasets/processed/queries/expanded/fr_train_en_translated_expanded.json
```

## License

All the contents of this repository are shared using the [Creative Commons Attribution-ShareAlike 4.0 International License](http://creativecommons.org/licenses/by-sa/4.0/).

![CC logo](https://i.creativecommons.org/l/by-sa/4.0/88x31.png)
