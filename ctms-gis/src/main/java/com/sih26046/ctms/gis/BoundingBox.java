package com.sih26046.ctms.gis;

/**
 * A viewport, {@code west,south,east,north} — the order GeoJSON's own {@code bbox} member
 * uses, so a frontend can pass a map's bounds straight through without reordering them.
 */
public record BoundingBox(double west, double south, double east, double north) {

    static BoundingBox parse(String raw) {
        String[] parts = raw.split(",");
        if (parts.length != 4) {
            throw new IllegalArgumentException(
                    "bbox must be 'west,south,east,north'; got '" + raw + "'");
        }
        try {
            return new BoundingBox(
                    Double.parseDouble(parts[0].trim()),
                    Double.parseDouble(parts[1].trim()),
                    Double.parseDouble(parts[2].trim()),
                    Double.parseDouble(parts[3].trim()));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "bbox must be four numbers 'west,south,east,north'; got '" + raw + "'", e);
        }
    }

    boolean contains(double longitude, double latitude) {
        return longitude >= west && longitude <= east && latitude >= south && latitude <= north;
    }
}
