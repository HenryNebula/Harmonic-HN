"""Configuration via environment variables (HTRANS_*), with .env support."""

from __future__ import annotations

from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict

DEFAULT_LANGUAGES = ["zh-Hans"]


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="HTRANS_", env_file=".env", extra="ignore")

    openrouter_api_key: str = ""
    openrouter_base_url: str = "https://openrouter.ai/api/v1"
    model: str = "openai/gpt-4o-mini"

    # Comma-separated BCP-47 tags matching the app's TranslationLanguage.storedValue values.
    languages: str = ",".join(DEFAULT_LANGUAGES)

    top_n: int = 10
    poll_minutes: float = 15.0
    max_comments_per_story: int = 500
    concurrency: int = 4

    db_path: str = "translations.db"
    host: str = "0.0.0.0"
    port: int = 8362

    request_timeout_seconds: float = 90.0
    max_output_tokens: int = 4000

    @property
    def language_list(self) -> list[str]:
        return [tag.strip() for tag in self.languages.split(",") if tag.strip()]


@lru_cache
def get_settings() -> Settings:
    return Settings()
