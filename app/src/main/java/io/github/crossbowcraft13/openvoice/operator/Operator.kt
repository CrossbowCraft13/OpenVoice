package io.github.crossbowcraft13.openvoice.operator

import android.content.Context

data class OperatorResult(val success: Boolean, val message: String = "")

interface Operator {
    val id: String
    suspend fun execute(context: Context, params: Map<String, String>): OperatorResult
}
