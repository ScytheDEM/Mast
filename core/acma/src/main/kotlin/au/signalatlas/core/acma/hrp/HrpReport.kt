package au.signalatlas.core.acma.hrp

import kotlinx.serialization.Serializable

/** Outcome of one HRP import (HRP import rule 6). [rows] counts every data row read (header excluded). */
@Serializable
data class HrpReport(
    val devices: Int,
    val patterns: Int,
    val skippedIncomplete: Int,
    val skippedUnknown: Int,
    val rows: Long,
)
