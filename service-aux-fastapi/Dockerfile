FROM docker.1ms.run/library/python:3.13-slim
WORKDIR /app

# Use Douban PyPI mirror to speed up package installs inside the container.
# We set both environment variables and a global pip config so that pip and
# any pip-compatible tooling pick up the mirror.
ENV PIP_INDEX_URL=https://pypi.org/simple
ENV PIP_TRUSTED_HOST=pypi.org

# Write a global pip config so pip respects the mirror even if env vars are not
# passed through during build or runtime.
RUN printf '[global]\nindex-url = https://pypi.org/simple\ntrusted-host = pypi.org\n' > /etc/pip.conf

COPY requirements.txt /app/
RUN pip install --no-cache-dir -r requirements.txt
COPY app /app/app
RUN mkdir -p /app/storage
EXPOSE 8432
ENV UVICORN_WORKERS=1
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8432"]
