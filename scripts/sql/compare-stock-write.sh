#!/bin/sh
# Run under postgres in a disposable postgres:16-alpine container, with no database volume mounted.
set -eu
work=$(mktemp -d)
export PGHOST="$work" PGDATABASE=postgres
initdb -D "$work/data" --auth=trust >/dev/null
pg_ctl -D "$work/data" -l "$work/postgres.log" -o "-k $work -c listen_addresses=''" -w start >/dev/null
trap 'pg_ctl -D "$work/data" -m fast -w stop >/dev/null' EXIT

psql -Xq -v ON_ERROR_STOP=1 <<'SQL'
CREATE TABLE inventory_probe (
    id bigint PRIMARY KEY, price numeric(19,4) NOT NULL, stock integer NOT NULL,
    version bigint NOT NULL DEFAULT 0, status text NOT NULL DEFAULT 'ACTIVE',
    updated_at timestamptz NOT NULL DEFAULT now()
);
SQL

cat >"$work/locked.sql" <<'SQL'
\set sku random(1, :skus)
BEGIN;
SELECT id, price, stock, version, status, updated_at FROM inventory_probe WHERE id = :sku FOR NO KEY UPDATE;
UPDATE inventory_probe SET stock=stock-1, version=version+1, updated_at=clock_timestamp(),
    status=CASE WHEN stock=1 THEN 'OUT_OF_STOCK' ELSE 'ACTIVE' END WHERE id=:sku;
COMMIT;
SQL

cat >"$work/atomic.sql" <<'SQL'
\set sku random(1, :skus)
BEGIN;
UPDATE inventory_probe SET stock=stock-1, version=version+1, updated_at=clock_timestamp(),
    status=CASE WHEN stock=1 THEN 'OUT_OF_STOCK' ELSE 'ACTIVE' END
    WHERE id=:sku AND stock>=1 AND status='ACTIVE' RETURNING price;
COMMIT;
SQL

echo 'skus,method,transactions,tps,p95_ms,p99_ms,units_reserved'
for skus in 1 1000; do
    for method in locked atomic atomic locked; do
        psql -Xq -v ON_ERROR_STOP=1 -c "TRUNCATE inventory_probe; INSERT INTO inventory_probe(id,price,stock) SELECT i,10,10000000 FROM generate_series(1,$skus) i; ANALYZE inventory_probe"
        pgbench -n -c 8 -j 2 -T 1 -D skus="$skus" -M prepared -f "$work/$method.sql" >/dev/null 2>&1
        before=$(psql -XAtqc 'SELECT sum(stock) FROM inventory_probe')
        prefix=$(mktemp -d "$work/samples.XXXXXX")/tx
        pgbench -n -c 8 -j 2 -T 3 -D skus="$skus" --random-seed=42 -M prepared -l --log-prefix="$prefix" \
            -f "$work/$method.sql" >"$work/summary" 2>&1
        transactions=$(awk '/number of transactions actually processed:/ {print $NF}' "$work/summary")
        tps=$(awk '/^tps =/ {print $3}' "$work/summary")
        [ -n "$transactions" ] && [ -n "$tps" ]
        percentiles=$(awk '{print $3}' "$prefix".* | sort -n | awk '{a[NR]=$1} END {printf "%.3f,%.3f", a[int((NR*95+99)/100)]/1000, a[int((NR*99+99)/100)]/1000}')
        after=$(psql -XAtqc 'SELECT sum(stock) FROM inventory_probe')
        reserved=$((before-after))
        [ "$reserved" = "$transactions" ]
        echo "$skus,$method,$transactions,$tps,$percentiles,$reserved"
    done
done
