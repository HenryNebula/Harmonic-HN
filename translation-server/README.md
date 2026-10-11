# Harmonic translation server

A small self-hosted FastAPI service that pre-translates Hacker News comment threads and
serves them as a cache to the Harmonic fork's comment-translation feature. It polls the HN
front page, walks each top story's comment tree, translates every comment through
OpenRouter, and stores `commentId → translated HTML` in SQLite. The app checks this server
first (Settings → AI features → Comment translation → Translation cache server) and falls
back to direct OpenRouter calls for anything missing.

## Setup

```sh
cd translation-server
cp .envrc.example .envrc   # adjust cache/venv paths for your machine
direnv allow
uv sync                    # creates the project environment and installs dependencies
```

Configuration lives in environment variables (optionally in a gitignored `.env` file):

| Variable | Default | Purpose |
| --- | --- | --- |
| `HTRANS_OPENROUTER_API_KEY` | *(empty)* | OpenRouter API key; required for translation |
| `HTRANS_MODEL` | `openai/gpt-4o-mini` | Chat model used for translation |
| `HTRANS_LANGUAGES` | `zh-Hans` | Comma-separated BCP-47 tags matching the app's language picker |
| `HTRANS_TOP_N` | `10` | How many front-page stories to track |
| `HTRANS_POLL_MINUTES` | `15` | Poll cadence |
| `HTRANS_MAX_COMMENTS_PER_STORY` | `500` | Comment-tree cap per story |
| `HTRANS_CONCURRENCY` | `4` | Parallel translation requests |
| `HTRANS_DB_PATH` | `translations.db` | SQLite database location |
| `HTRANS_HOST` / `HTRANS_PORT` | `0.0.0.0` / `8362` | Bind address |

## Run

```sh
uv run harmonic-translation-server
# or: uv run uvicorn translation_server.api:app --host 0.0.0.0 --port 8362
```

Then point the app at the server from a device on the same network, e.g.
`http://192.168.1.10:8362` (the Android app permits cleartext traffic). iOS would need an
App Transport Security exception for plain-HTTP LAN addresses.

## API

- `GET /health` — status, tracked story count, configured languages.
- `GET /v1/translations/{story_id}?language=zh-Hans` — `{"comments": {"<id>": "<html>"}, "complete": bool}`;
  `404` when the story is not tracked (the app then translates directly).
- `POST /v1/translate/{story_id}` — queue an on-demand refresh for any story (manual use).

## Tests

```sh
uv run pytest
```

## Keeping prompts in sync

`translation_server/translator.py` (`TRANSLATION_PROMPT`, `build_input`) intentionally
duplicates `core/src/commonMain/kotlin/com/simon/harmonichackernews/summary/CommentTranslator.kt`
(`translationPrompt`, `buildTranslationInput`). If you edit one side, edit the other —
translations coming from the cache should behave exactly like direct ones.
