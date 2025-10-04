from loguru import logger
import sys
from pathlib import Path
import logging


LOG_DIR = Path("./logs")
LOG_DIR.mkdir(parents=True, exist_ok=True)


def _level_from_record(record):
    # preserve original level name, fallback to INFO
    return record.levelname if hasattr(record, "levelname") else "INFO"


class InterceptHandler(logging.Handler):
    """Intercept standard library logging and forward to loguru.

    This allows uvicorn and other libraries that use the logging module to
    be captured by loguru and use the same sinks/formatting.
    """

    def emit(self, record):
        try:
            level = record.levelname
        except Exception:
            level = "INFO"
        # find caller depth so loguru shows correct origin
        logger_opt = logger.bind()
        logger_opt.opt(depth=6, exception=record.exc_info).log(level, record.getMessage())


# Remove default handlers to avoid duplicate logs
logger.remove()

# Console sink: human readable, colorized
logger.add(
    sys.stdout,
    level="INFO",
    format="<green>{time:YYYY-MM-DD HH:mm:ss.SSS}</green> | <level>{level: <8}</level> | {message}",
    colorize=True,
)

# File sink: daily rotation, JSON serialized, asynchronous (enqueue)
logger.add(
    str(LOG_DIR / "app-{time:YYYY-MM-DD}.log"),
    level="INFO",
    rotation="00:00",
    retention="14 days",
    serialize=True,
    enqueue=True,
    compression="zip",
)


# Intercept standard logging
logging.root.handlers = [InterceptHandler()]
for name in ("uvicorn.access", "uvicorn.error", "fastapi", "sqlalchemy.engine"):
    l = logging.getLogger(name)
    l.handlers = [InterceptHandler()]
    l.setLevel(logging.INFO)


def configure(level: str = "INFO"):
    """Optional manual reconfiguration entrypoint.

    Call `configure()` early in application startup if you want to change
    the default level programmatically.
    """
    logger.remove()
    logger.add(sys.stdout, level=level, format="{time:YYYY-MM-DD HH:mm:ss} | {level} | {message}")
    logger.add(str(LOG_DIR / "app-{time:YYYY-MM-DD}.log"), level=level, rotation="00:00", serialize=True, enqueue=True)
