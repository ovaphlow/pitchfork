from fastapi import FastAPI, UploadFile, File, Depends, HTTPException, Response
from fastapi.responses import FileResponse
from pathlib import Path
import uuid
import aiofiles
from .settings import settings
# initialize logging (Loguru)
from . import logger  # noqa: F401
from .crud import init_db, create_file_meta, list_files, get_file_meta, delete_file_meta
from .deps import require_api_key
from .schemas import FileRead
from typing import List

app = FastAPI(title="aux-fastapi-files")


@app.on_event("startup")
def startup_event():
    # Ensure storage dir exists and DB initialized
    settings.STORAGE_DIR.mkdir(parents=True, exist_ok=True)
    init_db()


@app.post("/pitchfork-api/upload", response_model=FileRead, dependencies=[Depends(require_api_key)])
async def upload(file: UploadFile = File(...)):
    # ensure filename is safe and generate a unique relative path (to avoid collisions)
    safe_name = Path(file.filename).name
    if safe_name != file.filename or not safe_name:
        raise HTTPException(status_code=400, detail="Invalid filename")

    unique_name = f"{uuid.uuid4().hex}_{safe_name}"
    # relative storage key (no leading slash)
    rel_path = unique_name
    dest = settings.STORAGE_DIR / rel_path

    # write file to disk
    async with aiofiles.open(dest, "wb") as out_file:
        content = await file.read()
        await out_file.write(content)

    # store relative path in DB so records are portable
    meta = create_file_meta(filename=file.filename, content_type=file.content_type, size=len(content), path=rel_path)
    return meta


@app.get("/pitchfork-api/files", response_model=List[FileRead], dependencies=[Depends(require_api_key)])
def files_list():
    return list_files()


@app.get("/pitchfork-api/files/{file_id}/download", dependencies=[Depends(require_api_key)])
def download(file_id: str):
    meta = get_file_meta(file_id)
    if not meta:
        raise HTTPException(status_code=404, detail="file not found")
    # resolve storage path: support legacy absolute paths and new relative keys
    storage_dir = settings.STORAGE_DIR.resolve()
    sp = Path(meta.storage_path)
    # storage_path must be a relative key only
    if sp.is_absolute():
        raise HTTPException(status_code=400, detail="Invalid file path")

    abs_path = (storage_dir / sp).resolve()

    # ensure the file is inside STORAGE_DIR to avoid path traversal
    try:
        if not abs_path.is_relative_to(storage_dir):
            raise HTTPException(status_code=400, detail="Invalid file path")
    except Exception:
        try:
            abs_path.relative_to(storage_dir)
        except Exception:
            raise HTTPException(status_code=400, detail="Invalid file path")

    return FileResponse(path=str(abs_path), filename=meta.filename, media_type=meta.content_type)


@app.delete("/pitchfork-api/files/{file_id}", dependencies=[Depends(require_api_key)])
def delete(file_id: str):
    meta = get_file_meta(file_id)
    if not meta:
        raise HTTPException(status_code=404, detail="file not found")
    # remove from disk (support legacy absolute paths and new relative keys)
    storage_dir = settings.STORAGE_DIR.resolve()
    sp = Path(meta.storage_path)
    # storage_path must be a relative key only
    if sp.is_absolute():
        raise HTTPException(status_code=400, detail="Invalid file path")

    abs_path = (storage_dir / sp).resolve()
    try:
        if not abs_path.is_relative_to(storage_dir):
            raise HTTPException(status_code=400, detail="Invalid file path")
    except Exception:
        try:
            abs_path.relative_to(storage_dir)
        except Exception:
            raise HTTPException(status_code=400, detail="Invalid file path")

    # remove from disk
    try:
        abs_path.unlink()
    except FileNotFoundError:
        # continue to remove metadata even if disk already missing
        pass
    delete_file_meta(file_id)
    return Response(status_code=204)



@app.get("/health")
def health():
    return {"status": "ok"}
