ALTER TABLE venues ADD COLUMN public_slug VARCHAR(64) NULL;
ALTER TABLE venues ADD COLUMN catalog_listed BOOLEAN NOT NULL DEFAULT TRUE;
CREATE TABLE venue_public_addresses (
    slug VARCHAR(64) PRIMARY KEY,
    venue_id BIGINT NOT NULL
);
CREATE INDEX idx_venue_public_addresses_venue ON venue_public_addresses (venue_id);
CREATE UNIQUE INDEX uq_venues_public_slug ON venues (public_slug);
