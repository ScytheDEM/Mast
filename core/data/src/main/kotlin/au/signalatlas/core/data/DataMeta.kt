package au.signalatlas.core.data

import au.signalatlas.core.acma.ImportReport
import java.time.Instant

/** Provenance of the loaded tower DB, read from its `meta` table. */
data class DataMeta(val extractStamp: String, val importedAt: Instant, val report: ImportReport)
