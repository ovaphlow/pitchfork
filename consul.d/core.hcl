service {
  name = "core"
  id   = "core-1"
  address = "svc-core"
  port = 8431

  tags = [
    "traefik.enable=true",
    "traefik.http.routers.core.rule=PathPrefix(`/core`)",
    "traefik.http.routers.core.entrypoints=web",
    "traefik.http.services.core.loadbalancer.server.port=8431",
  ]

  connect { 
    sidecar_service { }
  }

  checks = [
    {
      name     = "core-http"
      http     = "http://localhost:8431/health"
      interval = "10s"
      timeout  = "2s"
    }
  ]
}
