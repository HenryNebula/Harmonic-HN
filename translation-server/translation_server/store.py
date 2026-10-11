"""SQLite persistence for stories, comments, and their translations."""

from __future__ import annotations

import hashlib
import time
from dataclasses import dataclass

import aiosqlite

from .hn import HnComment, HnStory

SCHEMA = """
CREATE TABLE IF NOT EXISTS stories (
    id INTEGER PRIMARY KEY,
    title TEXT NOT NULL DEFAULT '',
    text TEXT NOT NULL DEFAULT '',
    by TEXT NOT NULL DEFAULT '',
    url TEXT NOT NULL DEFAULT '',
    updated_at REAL NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS comments (
    id INTEGER PRIMARY KEY,
    story_id INTEGER NOT NULL REFERENCES stories(id) ON DELETE CASCADE,
    author TEXT NOT NULL DEFAULT '',
    parent_id INTEGER NOT NULL DEFAULT 0,
    html TEXT NOT NULL DEFAULT '',
    created_at INTEGER NOT NULL DEFAULT 0,
    source_revision TEXT NOT NULL DEFAULT ''
);
CREATE TABLE IF NOT EXISTS translations (
    comment_id INTEGER NOT NULL REFERENCES comments(id) ON DELETE CASCADE,
    lang TEXT NOT NULL,
    translated_html TEXT NOT NULL,
    model TEXT NOT NULL DEFAULT '',
    created_at REAL NOT NULL DEFAULT 0,
    PRIMARY KEY (comment_id, lang)
);
CREATE INDEX IF NOT EXISTS idx_comments_story ON comments(story_id);
"""


def source_revision(html: str) -> str:
    return hashlib.sha1(html.encode("utf-8")).hexdigest()[:16]


@dataclass
class StoredTranslation:
    comment_id: int
    html: str


class TranslationStore:
    def __init__(self, db_path: str):
        self.db_path = db_path
        self._db: aiosqlite.Connection | None = None

    async def connect(self) -> None:
        self._db = await aiosqlite.connect(self.db_path)
        self._db.row_factory = aiosqlite.Row
        await self._db.execute("PRAGMA journal_mode=WAL")
        await self._db.execute("PRAGMA foreign_keys=ON")
        await self._db.executescript(SCHEMA)
        await self._db.commit()

    async def close(self) -> None:
        if self._db is not None:
            await self._db.close()
            self._db = None

    @property
    def db(self) -> aiosqlite.Connection:
        if self._db is None:
            raise RuntimeError("TranslationStore.connect() has not been called")
        return self._db

    async def upsert_story(self, story: HnStory) -> None:
        await self.db.execute(
            """
            INSERT INTO stories (id, title, text, by, url, updated_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                title = excluded.title,
                text = excluded.text,
                by = excluded.by,
                url = excluded.url,
                updated_at = excluded.updated_at
            """,
            (story.id, story.title, story.text, story.by, story.url, time.time()),
        )
        await self.db.commit()

    async def upsert_comments(self, story_id: int, comments: list[HnComment]) -> None:
        for comment in comments:
            await self.db.execute(
                """
                INSERT INTO comments (id, story_id, author, parent_id, html, created_at, source_revision)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    story_id = excluded.story_id,
                    author = excluded.author,
                    parent_id = excluded.parent_id,
                    html = excluded.html,
                    created_at = excluded.created_at,
                    source_revision = excluded.source_revision
                """,
                (
                    comment.id,
                    story_id,
                    comment.author,
                    comment.parent_id,
                    comment.html,
                    comment.created_at,
                    source_revision(comment.html),
                ),
            )
        await self.db.commit()

    async def get_story(self, story_id: int) -> HnStory | None:
        cursor = await self.db.execute(
            "SELECT id, title, text, by, url FROM stories WHERE id = ?", (story_id,)
        )
        row = await cursor.fetchone()
        if row is None:
            return None
        kids_cursor = await self.db.execute(
            "SELECT id FROM comments WHERE parent_id = ? ORDER BY created_at", (story_id,)
        )
        kids = [kid["id"] for kid in await kids_cursor.fetchall()]
        return HnStory(
            id=row["id"],
            title=row["title"],
            text=row["text"],
            by=row["by"],
            url=row["url"],
            kids=kids,
        )

    async def get_comment(self, comment_id: int) -> HnComment | None:
        cursor = await self.db.execute(
            "SELECT id, story_id, author, parent_id, html, created_at FROM comments WHERE id = ?",
            (comment_id,),
        )
        row = await cursor.fetchone()
        if row is None:
            return None
        return HnComment(
            id=row["id"],
            parent_id=row["parent_id"],
            author=row["author"],
            html=row["html"],
            created_at=row["created_at"],
        )

    async def story_comment_count(self, story_id: int) -> int:
        cursor = await self.db.execute(
            "SELECT COUNT(*) AS count FROM comments WHERE story_id = ?", (story_id,)
        )
        row = await cursor.fetchone()
        return int(row["count"])

    async def pending_translations(
        self, story_id: int, language: str
    ) -> list[HnComment]:
        """Comments of the story without an up-to-date translation in [language]."""
        cursor = await self.db.execute(
            """
            SELECT c.id, c.parent_id, c.author, c.html, c.created_at
            FROM comments c
            LEFT JOIN translations t
                ON t.comment_id = c.id AND t.lang = ?
            WHERE c.story_id = ? AND c.html != ''
              AND (t.comment_id IS NULL OR t.translated_html = '')
            """,
            (language, story_id),
        )
        return [
            HnComment(
                id=row["id"],
                parent_id=row["parent_id"],
                author=row["author"],
                html=row["html"],
                created_at=row["created_at"],
            )
            for row in await cursor.fetchall()
        ]

    async def save_translation(
        self, comment_id: int, language: str, html: str, model: str
    ) -> None:
        await self.db.execute(
            """
            INSERT INTO translations (comment_id, lang, translated_html, model, created_at)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT(comment_id, lang) DO UPDATE SET
                translated_html = excluded.translated_html,
                model = excluded.model,
                created_at = excluded.created_at
            """,
            (comment_id, language, html, model, time.time()),
        )
        await self.db.commit()

    async def translations_for_story(
        self, story_id: int, language: str
    ) -> list[StoredTranslation]:
        cursor = await self.db.execute(
            """
            SELECT t.comment_id, t.translated_html
            FROM translations t
            JOIN comments c ON c.id = t.comment_id
            WHERE c.story_id = ? AND t.lang = ? AND t.translated_html != ''
            """,
            (story_id, language),
        )
        return [
            StoredTranslation(comment_id=row["comment_id"], html=row["translated_html"])
            for row in await cursor.fetchall()
        ]
