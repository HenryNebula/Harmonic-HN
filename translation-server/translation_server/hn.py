"""Hacker News Firebase API client (top stories plus bounded comment trees)."""

from __future__ import annotations

import asyncio
from dataclasses import dataclass, field
from typing import Any

import httpx

HN_BASE = "https://hacker-news.firebaseio.com/v0"


@dataclass
class HnStory:
    id: int
    title: str = ""
    text: str = ""
    by: str = ""
    url: str = ""
    kids: list[int] = field(default_factory=list)


@dataclass
class HnComment:
    id: int
    parent_id: int
    author: str = ""
    html: str = ""
    created_at: int = 0


async def fetch_top_story_ids(client: httpx.AsyncClient, limit: int) -> list[int]:
    response = await client.get(f"{HN_BASE}/topstories.json")
    response.raise_for_status()
    return [int(item) for item in response.json()[:limit]]


async def fetch_item(client: httpx.AsyncClient, item_id: int) -> dict[str, Any] | None:
    response = await client.get(f"{HN_BASE}/item/{item_id}.json")
    if response.status_code == 404:
        return None
    response.raise_for_status()
    payload = response.json()
    return payload if isinstance(payload, dict) else None


async def fetch_story(client: httpx.AsyncClient, story_id: int) -> HnStory | None:
    payload = await fetch_item(client, story_id)
    if payload is None or payload.get("type") not in ("story", "job", "poll"):
        return None
    return HnStory(
        id=int(payload.get("id", story_id)),
        title=payload.get("title") or "",
        text=payload.get("text") or "",
        by=payload.get("by") or "",
        url=payload.get("url") or "",
        kids=[int(kid) for kid in payload.get("kids", []) if isinstance(kid, int)],
    )


async def _fetch_many(client: httpx.AsyncClient, ids: list[int]) -> list[dict[str, Any] | None]:
    semaphore = asyncio.Semaphore(16)

    async def guarded(item_id: int) -> dict[str, Any] | None:
        async with semaphore:
            try:
                return await fetch_item(client, item_id)
            except httpx.HTTPError:
                return None

    return list(await asyncio.gather(*(guarded(item_id) for item_id in ids)))


async def fetch_story_comments(
    client: httpx.AsyncClient,
    story: HnStory,
    max_comments: int,
) -> list[HnComment]:
    """Breadth-first walk of the story's comment tree, capped at [max_comments]."""
    comments: list[HnComment] = []
    frontier: list[tuple[int, int]] = [(kid, story.id) for kid in story.kids]
    seen: set[int] = set()
    while frontier and len(comments) < max_comments:
        batch = frontier[: max_comments - len(comments)]
        frontier = frontier[len(batch):]
        payloads = await _fetch_many(client, [item_id for item_id, _ in batch])
        next_level: list[tuple[int, int]] = []
        for (item_id, parent_id), payload in zip(batch, payloads):
            if payload is None or item_id in seen:
                continue
            seen.add(item_id)
            if payload.get("dead") or payload.get("deleted"):
                continue
            comments.append(
                HnComment(
                    id=item_id,
                    parent_id=parent_id,
                    author=payload.get("by") or "",
                    html=payload.get("text") or "",
                    created_at=int(payload.get("time") or 0),
                ),
            )
            next_level.extend(
                (kid, item_id) for kid in payload.get("kids", []) if isinstance(kid, int)
            )
        frontier.extend(next_level)
    return comments
