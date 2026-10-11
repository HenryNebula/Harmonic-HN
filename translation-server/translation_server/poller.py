"""Background poller: tracks the HN front page and pre-translates its comments."""

from __future__ import annotations

import asyncio
import contextlib
import logging

import httpx

from .config import Settings
from .hn import fetch_story, fetch_story_comments, fetch_top_story_ids
from .store import TranslationStore
from .translator import TranslationError, build_input, parent_chain, translate_comment

logger = logging.getLogger("harmonic-translation-server.poller")


class Poller:
    def __init__(self, settings: Settings, store: TranslationStore):
        self.settings = settings
        self.store = store
        self._task: asyncio.Task | None = None
        self._wake = asyncio.Event()

    def start(self) -> None:
        if self._task is None or self._task.done():
            self._task = asyncio.create_task(self._run(), name="translation-poller")

    async def stop(self) -> None:
        if self._task is not None:
            self._task.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await self._task
            self._task = None

    def wake(self) -> None:
        self._wake.set()

    async def _run(self) -> None:
        # First poll immediately, then on the configured cadence or on demand.
        while True:
            try:
                await self.poll_once()
            except asyncio.CancelledError:
                raise
            except Exception:  # noqa: BLE001 - the poller must survive any cycle failure
                logger.exception("poll cycle failed")
            try:
                await asyncio.wait_for(self._wake.wait(), timeout=self.settings.poll_minutes * 60)
            except asyncio.TimeoutError:
                pass
            self._wake.clear()

    async def poll_once(self) -> None:
        languages = self.settings.language_list
        if not languages or not self.settings.openrouter_api_key:
            logger.warning("skipping poll: no languages or API key configured")
            return
        async with httpx.AsyncClient() as client:
            story_ids = await fetch_top_story_ids(client, self.settings.top_n)
            logger.info("tracking %d stories: %s", len(story_ids), story_ids)
            for story_id in story_ids:
                await self.refresh_story(client, story_id, languages)

    async def refresh_story(
        self, client: httpx.AsyncClient, story_id: int, languages: list[str] | None = None
    ) -> int:
        """Fetches one story's comments and translates anything missing. Returns job count."""
        languages = languages or self.settings.language_list
        story = await fetch_story(client, story_id)
        if story is None:
            return 0
        await self.store.upsert_story(story)
        comments = await fetch_story_comments(client, story, self.settings.max_comments_per_story)
        await self.store.upsert_comments(story_id, comments)

        total = 0
        semaphore = asyncio.Semaphore(self.settings.concurrency)

        async def work(comment_id: int, language: str) -> None:
            async with semaphore:
                await self._translate_one(client, comment_id, story, language)

        jobs: list[asyncio.Task] = []
        for language in languages:
            pending = await self.store.pending_translations(story_id, language)
            total += len(pending)
            for comment in pending:
                jobs.append(asyncio.create_task(work(comment.id, language)))
        if jobs:
            await asyncio.gather(*jobs)
        logger.info("story %s: %s translation jobs", story_id, total)
        return total

    async def _translate_one(
        self,
        client: httpx.AsyncClient,
        comment_id: int,
        story,
        language: str,
    ) -> None:
        comment = await self.store.get_comment(comment_id)
        if comment is None or not comment.html.strip():
            return
        try:
            parents = await parent_chain(self.store, comment, story.id)
            user_input = build_input(story, comment, parents)
            translated = await translate_comment(
                client=client,
                base_url=self.settings.openrouter_base_url,
                api_key=self.settings.openrouter_api_key,
                model=self.settings.model,
                language=language,
                user_input=user_input,
                max_output_tokens=self.settings.max_output_tokens,
                timeout=self.settings.request_timeout_seconds,
            )
            await self.store.save_translation(comment_id, language, translated, self.settings.model)
        except TranslationError as error:
            logger.warning("translation failed for %s/%s: %s", comment_id, language, error)
