-- Results of the free list per ISP (AS number) and config (hash of the link without its name)
CREATE TABLE IF NOT EXISTS stats (
  asn TEXT NOT NULL,
  h TEXT NOT NULL,
  ok INTEGER NOT NULL DEFAULT 0,
  fail INTEGER NOT NULL DEFAULT 0,
  ms INTEGER NOT NULL DEFAULT 0,
  updated INTEGER NOT NULL,
  PRIMARY KEY (asn, h)
);
