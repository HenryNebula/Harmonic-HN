"""Store round-trip tests against an in-memory SQLite database."""

from __future__ import annotations

import pytest

from translation_server.hn import HnComment, HnStory
from translation_server.store import TranslationStore, source_revision


@pytest.fixture()
async def store() -> TranslationStore:
    store = TranslationStore(":memory:")
    await store.connect()
    yield store
    await store.close()


async def test_story_and_comment_round_trip(store: TranslationStore):
    story = HnStory(id=1, title="Title", text="<p>Body</p>", by="op", kids=[10])
    await store.upsert_story(story)
    await store.upsert_comments(1, [
        HnComment(id=10, parent_id=1, author="alice", html="<p>Root</p>"),
        HnComment(id=11, parent_id=10, author="bob", html="<p>Child</p>"),
    ])

    loaded = await store.get_story(1)
    assert loaded is not None and loaded.title == "Title"
    assert await store.story_comment_count(1) == 2
    comment = await store.get_comment(11)
    assert comment is not None and comment.parent_id == 10

    assert await store.get_story(999) is None


async def test_pending_and_saved_translations(store: TranslationStore):
    await store.upsert_story(HnStory(id=1, kids=[10]))
    await store.upsert_comments(1, [
        HnComment(id=10, parent_id=1, html="<p>Hallo</p>"),
        HnComment(id=11, parent_id=1, html=""),  # empty comments are never pending
    ])

    pending = await store.pending_translations(1, "de")
    assert [comment.id for comment in pending] == [10]

    await store.save_translation(10, "de", "<p>Hello</p>", "test-model")
    assert await store.pending_translations(1, "de") == []
    assert await store.pending_translations(1, "zh-Hans") != []

    results = await store.translations_for_story(1, "de")
    assert [(r.comment_id, r.html) for r in results] == [(10, "<p>Hello</p>")]


async def test_revisions_follow_edits():
    assert source_revision("<p>a</p>") != source_revision("<p>b</p>")
    assert source_revision("<p>a</p>") == source_revision("<p>a</p>")
