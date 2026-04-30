# Search Engines (SE) - NEXA Group - CheckThat! CLEF 2026 Task 1

This repository will contains the code and papers produced in the Search Engines course. A.Y. 2025/2026 for the participation of the group NEXA at [CheckThat! 2026 Task 1](https://checkthat.gitlab.io/clef2026/task1/) at [CLEF 2026](https://clef2026.clef-initiative.eu/).

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

## Gemma translation server

The multilingual translation pipeline uses a local Python server that exposes
`google/translategemma-4b-it` through an HTTP endpoint consumed by the Java code.

From the `code` directory, create the virtual environment and install the
required packages.

macOS/Linux:

```bash
cd code
python3 -m venv scripts/.venv
scripts/.venv/bin/python3 -m pip install -r scripts/requirements-translategemma.txt
```

Windows PowerShell:

```powershell
cd code
py -3 -m venv scripts\.venv
.\scripts\.venv\Scripts\python.exe -m pip install -r scripts\requirements-translategemma.txt
```

If the `py` launcher is not available on Windows, use `python` instead.

Create the local environment file from the example and set your Hugging Face
token in it.

macOS/Linux:

```bash
cp scripts/translategemma.env.example scripts/translategemma.env
```

Windows PowerShell:

```powershell
Copy-Item .\scripts\translategemma.env.example .\scripts\translategemma.env
notepad .\scripts\translategemma.env
```

Then start the translation server:

macOS/Linux:

```bash
./scripts/run_translategemma_server.sh
```

Windows PowerShell:

```powershell
$env:HF_TOKEN = ((Get-Content .\scripts\translategemma.env | Where-Object { $_ -match '^HF_TOKEN=' } | Select-Object -First 1) -split '=', 2)[1]
.\scripts\.venv\Scripts\python.exe .\scripts\translategemma_server.py
```

The server listens on `http://127.0.0.1:8008` by default. A quick health check is:

macOS/Linux:

```bash
curl http://127.0.0.1:8008/health
```

Windows PowerShell:

```powershell
Invoke-RestMethod http://127.0.0.1:8008/health
```

The Java pipeline uses the settings in `code/src/main/config/config.yml`.
To enable document translation before indexing, set
`translateNonEnglishPublicationsToEnglish: true`.

## Query expansion server

Query expansion is served by a local Gemini-based HTTP server. The Java searcher
calls this server only when `enableQueryExpansion: true` is set in
`code/src/main/config/config.yml`.

From the repository root, create a Python virtual environment for the query
expansion server and install its dependency:

```bash
python3 -m venv code/scripts/queryExpansion/.venv
code/scripts/queryExpansion/.venv/bin/python3 -m pip install -r code/scripts/queryExpansion/requirements-queryexpansion.txt
```

Set the Gemini API key in `code/scripts/queryExpansion/queryexpansion.env`:

```bash
API_KEY=<your Gemini API key>
MODEL=gemini-1.5-flash
PORT=8001
```

Start the server:

```bash
code/scripts/queryExpansion/run_queryexpansion_server.sh
```

The server listens on `http://127.0.0.1:8001` by default. Health check:

```bash
curl http://127.0.0.1:8001/health
```

Manual expansion request:

```bash
curl -X POST http://127.0.0.1:8001/expand \
  -H "Content-Type: application/json" \
  -d '{"query":"COVID-19 mRNA vaccines increase myocarditis risk"}'
```

By default, server responses are cached in:

```text
code/scripts/queryExpansion/queryexpansion_cache.json
```

To materialize expanded queries as a dataset file, run:

```bash
python3 code/scripts/expand_claims.py \
  --claims-file datasets/processed/queries/translated/fr_train_en_translated.json \
  --output-file datasets/processed/queries/expanded/fr_train_en_translated_expanded.json
```

After creating the expanded file, update `topics` in
`code/src/main/config/config.yml` to:

```yaml
topics: datasets/processed/queries/expanded/fr_train_en_translated_expanded.json
```

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

Start the query expansion server if `enableQueryExpansion: true`:

```bash
code/scripts/queryExpansion/run_queryexpansion_server.sh
```

Start the translation server only if you are translating at runtime:

```bash
cd code
./scripts/run_translategemma_server.sh
cd ..
```

For the current processed-query setup, runtime claim translation is disabled
because `topics` already points to translated claims.

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
java -cp code/target/nexa-0.1-jar-with-dependencies.jar \
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
