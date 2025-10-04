try:
    from pydantic_settings import BaseSettings, SettingsConfigDict
except Exception:
    # Fallback for older pydantic versions where BaseSettings lived in pydantic
    from pydantic import BaseSettings
    # simple alias so model_config assignment below still works
    SettingsConfigDict = dict
from pathlib import Path
from typing import Optional


class Settings(BaseSettings):
    API_KEY: str = "change-me"
    API_KEY_HEADER: str = "X-API-Key"
    STORAGE_DIR: Path = Path("./storage")

    # Support either a full DATABASE_URL or individual PG_* settings
    DATABASE_URL: Optional[str] = None
    PG_USER: str = "postgres"
    PG_PASSWORD: str = "password"
    PG_HOST: str = "postgres"
    PG_PORT: int = 5432
    PG_DB: str = "auxfiles"

    # Pydantic v2 (pydantic-settings) uses `model_config` / SettingsConfigDict
    # Allow extra env vars to be ignored and load from .env file
    model_config = SettingsConfigDict({"env_file": ".env", "extra": "ignore"})

    # Allow configuring a project-level PyPI mirror via environment variables
    PIP_INDEX_URL: Optional[str] = None
    PIP_TRUSTED_HOST: Optional[str] = None

    def __init__(self, **values):
        super().__init__(**values)
        # Build a Postgres URL when DATABASE_URL not provided
        if not self.DATABASE_URL:
            self.DATABASE_URL = (
                f"postgresql+psycopg2://{self.PG_USER}:"
                f"{self.PG_PASSWORD}@{self.PG_HOST}:{self.PG_PORT}/{self.PG_DB}"
            )


settings = Settings()
