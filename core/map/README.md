# core:map

MapLibre map (`TowerMap`), bundled styles, tower layers and the coverage overlay.

## Coverage overlay: rendering path = GPU `color-relief` (spike passed)

Spike on MapLibre Android 13.7.1 (Vulkan artifact), emulator `sa_pixel`: a `raster-dem` source (`encoding: terrarium`)
fed a constant tile (elevation -90 dBm) through an OkHttp interceptor renders through a `color-relief` layer. Land
went from `#F6F5F2` to `(148,194,202)`, the expected blend of the interpolated colour at alpha 0.45. So the fallback
(pre-coloured raster PNGs, `TerrariumEncoder.encodeColored()`) was **not** needed.

- The Android API has `ColorReliefLayer`, `RasterDemSource` and `TileSet.encoding`; no style-JSON entry point for one
  source, so `CoverageLayer.source()` builds the same source as `sourceJson()`.
- The layer goes below the first label layer (above roads/water) and below all tower layers, `minzoom` 8.
- New URL template: source and layer are replaced, no style reload. Opacity change: only `color-relief-color`.
  The tile URL contains the view, so a view switch is a new template (cached tiles are reused, only re-encoded).
- Measured: MapLibre requests `z = floor(cameraZoom) + 1` (256 px tiles) plus a z8 ancestor, so
  `CoverageLayer.visibleTiles` permits the tiles at round(zoom) and floor(zoom)+1, each with all ancestors down to z8.
- MapLibre's HTTP client must be given before the first map; `MapHttp.install(client)` stores it and `TowerMap` applies
  it after `MapLibre.getInstance` (MapLibre rejects a client before that).
