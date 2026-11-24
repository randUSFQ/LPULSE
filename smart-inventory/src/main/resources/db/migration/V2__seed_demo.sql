-- Datos semilla de DEMO (fechas relativas a nov-2026; ajustar si se reutiliza despues).
insert into batch (sku, location, lot_code, expires_on, physical, reserved) values
  ('SKU-1001', 'BOD-UIO', 'L-OLD', date '2026-10-01', 20, 0),    -- vencido: FEFO lo ignora
  ('SKU-1001', 'BOD-UIO', 'L-B',   date '2026-12-01', 30, 0),    -- vence primero
  ('SKU-1001', 'BOD-UIO', 'L-A',   date '2026-12-15', 40, 5),
  ('SKU-1001', 'BOD-UIO', 'L-C',   date '2027-03-01', 100, 0),
  ('SKU-2002', 'BOD-UIO', 'M-1',   date '2027-01-10', 25, 0);

insert into reorder_config (sku, reorder_point, target_level, supplier) values
  ('SKU-1001', 60, 300, 'PROV-ANDINA'),
  ('SKU-2002', 20, 100, 'PROV-SIERRA');
