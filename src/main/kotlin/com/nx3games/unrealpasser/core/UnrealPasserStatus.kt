package com.nx3games.unrealpasser.core

import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.project.Project

enum class IndexState(val wireName: String) {
    READY("ready"),
    INDEXING("indexing"),
    UBT_IN_PROGRESS("ubt_in_progress"),
    ERROR("error"),
}

data class ApiError(
    val errorCode: String,
    val message: String,
    val source: String,
    val recoverable: Boolean,
) {
    fun toJsonMap(): Map<String, Any?> = mapOf(
        "errorCode" to errorCode,
        "message" to message,
        "source" to source,
        "recoverable" to recoverable,
    )
}

class ProviderFailureException(
    val apiError: ApiError,
    val httpStatus: Int = 503,
) : RuntimeException(apiError.message)

class UnrealPasserStatus(
    private val project: Project,
    private val projectRoot: String,
    private val pluginVersion: String,
) {
    private val unrealStatus = RiderUnrealStatusProbe(project)

    fun metadata(provider: String, indexStateOverride: IndexState? = null): Map<String, Any?> {
        val readiness = currentReadiness()
        return mapOf(
            "provider" to provider,
            "riderBuild" to ApplicationInfo.getInstance().build.asString(),
            "pluginVersion" to pluginVersion,
            "projectRoot" to projectRoot,
            "indexState" to (indexStateOverride ?: readiness.indexState).wireName,
            "partial" to false,
            "readiness" to readiness.toJsonMap(),
        )
    }

    fun currentIndexState(): IndexState {
        return currentReadiness().indexState
    }

    fun currentReadiness(): RiderUnrealReadiness = unrealStatus.read()
}
