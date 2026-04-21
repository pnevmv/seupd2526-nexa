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
required packages:

```bash
cd code
python3 -m venv scripts/.venv
scripts/.venv/bin/pip install -r scripts/requirements-translategemma.txt
```

Create the local environment file from the example and set your Hugging Face token:

```bash
cp scripts/translategemma.env.example scripts/translategemma.env
```

Then start the translation server:

```bash
./scripts/run_translategemma_server.sh
```

The server listens on `http://127.0.0.1:8008` by default. A quick health check is:

```bash
curl http://127.0.0.1:8008/health
```

The Java pipeline uses the settings in `code/src/main/config/config.yml`.
To enable document translation before indexing, set
`translateNonEnglishPublicationsToEnglish: true`.

## License

All the contents of this repository are shared using the [Creative Commons Attribution-ShareAlike 4.0 International License](http://creativecommons.org/licenses/by-sa/4.0/).

![CC logo](https://i.creativecommons.org/l/by-sa/4.0/88x31.png)
