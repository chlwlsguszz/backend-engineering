# Grafana Dashboard Exports

Export dashboards from Grafana UI and save the JSON files in this directory.

Recommended filename format:

- `search-api-overview.json`
- `search-latency-slo.json`
- `order-api-tps.json` — POST `/api/orders` TPS/RPS + latency histogram (k6 부하 테스트용)

After adding or changing dashboard JSON files, restart Grafana:

```powershell
docker compose restart grafana
```
