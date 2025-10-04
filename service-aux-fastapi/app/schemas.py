from pydantic import BaseModel
from typing import Optional, Dict, Any
from datetime import datetime


class FileRead(BaseModel):
    id: str
    filename: str
    content_type: Optional[str] = None
    size: Optional[int] = None
    relation_id: Optional[str] = None
    storage_backend: Optional[str] = None
    storage_path: Optional[str] = None
    status: Optional[str] = None
    checksum: Optional[str] = None
    record_meta: Optional[Dict[str, Any]] = None
    created_at: datetime
    uploaded_at: Optional[datetime] = None
    metadata_version: Optional[int] = None

    class Config:
        orm_mode = True
