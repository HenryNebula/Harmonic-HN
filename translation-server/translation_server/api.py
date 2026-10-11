"""HTTP API serving cached comment translations to the Harmonic fork."""

from __future__ import annotations

import asyncio
import logging
from contextlib import asynccontextmanager

import httpx
from fastapi import FastAPI, HTTPException, Query

from .config import get_settings
from .poller import Poller
from .store import TranslationStore

logger = logging.getLogger("harmonic-translation-server")

store = TranslationStore(get_settings().db_path)
poller = Poller(get_settings(), store)


@asynccontextmanager
async def lifespan(_: FastAPI):
    await store.connect()
    poller.start()
    yield
    await poller.stop()
    await store.close()


app = FastAPI(title="Harmonic translation server", lifespan=lifespan)


@app.get("/health")
async def health() -> dict:
    settings = get_settings()
    cursor = await store.db.execute("SELECT COUNT(*) AS count FROM stories")
    row = await cursor.fetchone()
    return {
        "status": "ok",
        "languages": settings.language_list,
        "stories_tracked": int(row["count"]),
        "api_key_configured": bool(settings.openrouter_api_key),
    }


@app.get("/v1/translations/{story_id}")
async def translations(story_id: int, language: str = Query(..., min_length=1)) -> dict:
    story = await store.get_story(story_id)
    if story is None:
        raise HTTPException(status_code=404, detail="story not tracked")
    results = await store.translations_for_story(story_id, language)
    comments = {str(result.comment_id): result.html for result in results}
    comment_count = await store.story_comment_count(story_id)
    with_text = await store.db.execute(
        "SELECT COUNT(*) AS count FROM comments WHERE story_id = ? AND html != ''",
        (story_id,),
    )
    translatable = int((await with_text.fetchone())["count"])
    return {
        "story": story_id,
        "language": language,
        "comments": comments,
        "complete": translatable > 0 and len(comments) >= translatable,
        "tracked_comments": comment_count,
    }


@app.post("/v1/translate/{story_id}")
async def queue_story(story_id: int) -> dict:
    """Queues an on-demand refresh for one story (manual use; the app does not call this)."""
    if story_id <= 0:
        raise HTTPException(status_code=400, detail="invalid story id")
    poller.wake()

    async def run() -> None:
        async with httpx.AsyncClient() as client:
            await poller.refresh_story(client, story_id)

    asyncio.create_task(run())
    return {"queued": True, "story": story_id}
