from sqlmodel import SQLModel, Session, create_engine, select
from .models import FileMeta
from .settings import settings
from typing import List, Optional
from datetime import datetime


# Create engine from the configured DATABASE_URL
engine = create_engine(settings.DATABASE_URL)


def init_db():
    SQLModel.metadata.create_all(engine)


def create_file_meta(*, filename: str, content_type: Optional[str], size: Optional[int], path: str, relation_id: Optional[str] = None, metadata: Optional[dict] = None) -> FileMeta:
    # normalize filename for case-insensitive constraints/search
    filename_normalized = filename.lower() if filename else None
    f = FileMeta(
        filename=filename,
        filename_normalized=filename_normalized,
        content_type=content_type,
        size=size,
        storage_backend="local",
        storage_path=path,
        record_meta=metadata or {},
        status="available",
        uploaded_at=datetime.utcnow(),
        relation_id=relation_id,
        metadata_version=1,
    )
    with Session(engine) as session:
        session.add(f)
        session.commit()
        session.refresh(f)
    return f


def list_files() -> List[FileMeta]:
    with Session(engine) as session:
        statement = select(FileMeta).order_by(FileMeta.created_at.desc())
        results = session.exec(statement).all()
    return results


def get_file_meta(file_id: str) -> Optional[FileMeta]:
    with Session(engine) as session:
        f = session.get(FileMeta, file_id)
    return f


def delete_file_meta(file_id: str) -> bool:
    with Session(engine) as session:
        f = session.get(FileMeta, file_id)
        if not f:
            return False
        session.delete(f)
        session.commit()
    return True


def update_file_metadata(file_id: str, new_metadata: dict) -> Optional[FileMeta]:
    """Update metadata for a file and bump metadata_version if changed.

    Returns the updated FileMeta or None if not found.
    """
    with Session(engine) as session:
        f = session.get(FileMeta, file_id)
        if not f:
            return None
        # compare shallow equality; for deep comparison callers can pre-normalize
        if f.record_meta != new_metadata:
            f.record_meta = new_metadata
            f.metadata_version = (f.metadata_version or 0) + 1
            session.add(f)
            session.commit()
            session.refresh(f)
        return f
