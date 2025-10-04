service {
  name = "aux"
  id   = "aux-1"
  address = "svc-aux-fastapi"
  port = 8432

  tags = [
    "traefik.enable=true",
    "traefik.http.routers.aux.rule=PathPrefix(`/aux`)",
    "traefik.http.routers.aux.entrypoints=web",
    "traefik.http.services.aux.loadbalancer.server.port=8432",
  ]

  connect { 
    sidecar_service { }
  }

  checks = [
    {
      name     = "aux-http"
      http     = "http://localhost:8432/health"
      interval = "10s"
      timeout  = "2s"
    }
  ]
}
