FROM docker.1ms.run/hashicorp/consul:1.21

# Copy static consul config into image so rootless Podman won't have mount permission issues
COPY ./consul.d /consul/config
# Ensure files are owned appropriately inside image
RUN chown -R consul:consul /consul/config || true
