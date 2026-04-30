# Grafana Dashboard Exports

Export dashboards from Grafana UI and save the JSON files in this directory.

Recommended filename format:

- `search-api-overview.json`
- `search-latency-slo.json`

These files are auto-loaded by Grafana through:

- `observability/grafana/provisioning/dashboards/dashboard.yml`

After adding or changing dashboard JSON files, restart Grafana:

```powershell
docker compose restart grafana
```
