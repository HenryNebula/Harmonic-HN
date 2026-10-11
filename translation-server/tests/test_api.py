"""API endpoint tests with an isolated store; the poller stays disabled."""

from __future__ import annotations

import httpx
import pytest
from httpx import ASGITransport

from translation_server import api
from translation_server.hn import HnComment, HnStory
from translation_server.store import TranslationStore


@pytest.fixture()
async def client(monkeypatch, tmp_path):
    test_store = TranslationStore(str(tmp_path / "test.db"))
    monkeypatch.setattr(api, "store", test_store)
    monkeypatch.setattr(api.poller, "start", lambda: None)
    await test_store.connect()
    transport = ASGITransport(app=api.app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as http:
        yield http, test_store
    await test_store.close()


async def _seed(store: TranslationStore) -> None:
    await store.upsert_story(HnStory(id=1, title="A post"))
    await store.upsert_comments(1, [
        HnComment(id=10, parent_id=1, html="<p>Hallo</p>"),
        HnComment(id=11, parent_id=1, html="<p>Welt</p>"),
    ])
    await store.save_translation(10, "de", "<p>Hello</p>", "m")


async def test_unknown_story_returns_404(client):
    http, _ = client
    response = await http.get("/v1/translations/999?language=de")
    assert response.status_code == 404


async def test_translations_serve_cached_map(client):
    http, store = client
    await _seed(store)
    response = await http.get("/v1/translations/1?language=de")
    assert response.status_code == 200
    payload = response.json()
    assert payload["story"] == 1
    assert payload["language"] == "de"
    assert payload["comments"] == {"10": "<p>Hello</p>"}
    assert payload["complete"] is False  # comment 11 is still missing
    assert payload["tracked_comments"] == 2


async def test_complete_when_every_translatable_comment_is_translated(client):
    http, store = client
    await _seed(store)
    await store.save_translation(11, "de", "<p>World</p>", "m")
    payload = (await http.get("/v1/translations/1?language=de")).json()
    assert payload["complete"] is True


async def test_health_reports_status(client):
    http, store = client
    await _seed(store)
    payload = (await http.get("/health")).json()
    assert payload["status"] == "ok"
    assert payload["stories_tracked"] == 1


async def test_language_parameter_is_required(client):
    http, _ = client
    response = await http.get("/v1/translations/1")
    assert response.status_code == 422
