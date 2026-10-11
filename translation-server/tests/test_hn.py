"""HN client tests over a fake Firebase API via httpx.MockTransport."""

from __future__ import annotations

import json

import httpx
import pytest

from translation_server.hn import (
    HnStory,
    fetch_story,
    fetch_story_comments,
    fetch_top_story_ids,
)

ITEMS = {
    1: {"id": 1, "type": "story", "title": "A post", "by": "op", "kids": [10, 20]},
    10: {"id": 10, "type": "comment", "by": "alice", "parent": 1, "text": "<p>Root</p>", "kids": [11]},
    11: {"id": 11, "type": "comment", "by": "bob", "parent": 10, "text": "<p>Child</p>", "kids": []},
    20: {"id": 20, "type": "comment", "by": "carol", "parent": 1, "text": "<p>Dead</p>", "dead": True},
}


def handler(request: httpx.Request) -> httpx.Response:
    path = request.url.path
    if path == "/v0/topstories.json":
        return httpx.Response(200, json=[1, 2, 3, 4, 5])
    if path.startswith("/v0/item/"):
        item_id = int(path.rstrip(".json").split("/")[-1])
        payload = ITEMS.get(item_id)
        if payload is None:
            return httpx.Response(404, json=None)
        return httpx.Response(200, json=payload)
    return httpx.Response(404)


@pytest.fixture()
def client() -> httpx.AsyncClient:
    return httpx.AsyncClient(transport=httpx.MockTransport(handler))


async def test_top_stories_are_limited(client):
    assert await fetch_top_story_ids(client, 3) == [1, 2, 3]


async def test_story_fetch_ignores_non_story_items(client):
    assert (await fetch_story(client, 10)) is None
    story = await fetch_story(client, 1)
    assert story is not None
    assert story.title == "A post"
    assert story.kids == [10, 20]


async def test_comment_walk_sets_parents_and_skips_dead(client):
    story = await fetch_story(client, 1)
    comments = await fetch_story_comments(client, story, max_comments=50)
    by_id = {comment.id: comment for comment in comments}
    assert set(by_id) == {10, 11}
    assert by_id[10].parent_id == 1
    assert by_id[11].parent_id == 10
    assert by_id[10].html == "<p>Root</p>"


async def test_comment_walk_respects_the_cap(client):
    story = await fetch_story(client, 1)
    comments = await fetch_story_comments(client, story, max_comments=1)
    assert [comment.id for comment in comments] == [10]
