FROM docker.1ms.run/library/traefik:v3

# Bake dynamic configuration into image to avoid bind-mount permission/watch problems under rootless Podman
COPY ./traefik/traefik.dynamic.yml /etc/traefik/dynamic.yml
RUN chmod a+r /etc/traefik/dynamic.yml || true
