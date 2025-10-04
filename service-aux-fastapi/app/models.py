from sqlmodel import SQLModel, Field
from typing import Optional, Dict, Any
from datetime import datetime
import uuid
from sqlalchemy import Column, text
from sqlalchemy.dialects.postgresql import JSONB, TIMESTAMP


class FileMeta(SQLModel, table=True):
    __tablename__ = "file_meta"
    id: str = Field(default_factory=lambda: str(uuid.uuid4()), primary_key=True)
    relation_id: Optional[str] = None
    filename: str
    filename_normalized: Optional[str] = Field(default=None, index=True)
    content_type: Optional[str] = None
    size: Optional[int] = None
    # storage location (object key / local path)
    storage_backend: str = Field(default="local")
    storage_path: str
    # extensible metadata stored as jsonb; use record_meta to avoid SQLAlchemy reserved 'metadata'
    record_meta: Dict[str, Any] = Field(
        default_factory=dict,
        sa_column=Column(JSONB, nullable=False, server_default=text("'{}'::jsonb")),
    )
    checksum: Optional[str] = None
    checksum_alg: str = Field(default="sha256")
    status: str = Field(default="uploading")
    encrypted: bool = Field(default=False)
    encryption_key_id: Optional[str] = None
    created_at: datetime = Field(
        default_factory=datetime.utcnow,
        sa_column=Column(TIMESTAMP(timezone=True), nullable=False, server_default=text("now()")),
    )
    uploaded_at: Optional[datetime] = Field(
        default=None,
        sa_column=Column(TIMESTAMP(timezone=True), nullable=True),
    )
    last_accessed: Optional[datetime] = Field(
        default=None,
        sa_column=Column(TIMESTAMP(timezone=True), nullable=True),
    )
    access_count: int = Field(default=0)
    retention_until: Optional[datetime] = Field(
        default=None,
        sa_column=Column(TIMESTAMP(timezone=True), nullable=True),
    )
    deleted_at: Optional[datetime] = Field(
        default=None,
        sa_column=Column(TIMESTAMP(timezone=True), nullable=True),
    )
    metadata_version: int = Field(default=1)
