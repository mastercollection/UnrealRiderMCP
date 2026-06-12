package com.nx3games.unrealpasser.core

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project

data class RiderUnrealReadiness(
    val frontendDumbMode: Boolean,
    val unrealModelAvailable: Boolean,
    val unrealSolution: Boolean?,
    val uProjectModel: Boolean?,
    val unrealEngineLocation: String?,
    val initialBackgroundIndexingCompleted: Boolean?,
    val ubtInProgress: Boolean?,
    val unrealDumbMode: Boolean?,
    val error: String?,
) {
    val indexState: IndexState
        get() = when {
            error != null -> IndexState.ERROR
            ubtInProgress == true -> IndexState.UBT_IN_PROGRESS
            frontendDumbMode || unrealDumbMode == true -> IndexState.INDEXING
            initialBackgroundIndexingCompleted == false -> IndexState.INDEXING
            else -> IndexState.READY
        }

    fun toJsonMap(): Map<String, Any?> = linkedMapOf(
        "frontendDumbMode" to frontendDumbMode,
        "unrealModelAvailable" to unrealModelAvailable,
        "unrealSolution" to unrealSolution,
        "uProjectModel" to uProjectModel,
        "unrealEngineLocation" to unrealEngineLocation,
        "initialBackgroundIndexingCompleted" to initialBackgroundIndexingCompleted,
        "ubtInProgress" to ubtInProgress,
        "unrealDumbMode" to unrealDumbMode,
        "error" to error,
    )
}

class RiderUnrealStatusProbe(private val project: Project) {
    fun read(): RiderUnrealReadiness {
        val frontendDumbMode = DumbService.isDumb(project)
        return runCatching {
            val solution = solution() ?: return RiderUnrealReadiness(
                frontendDumbMode = frontendDumbMode,
                unrealModelAvailable = false,
                unrealSolution = null,
                uProjectModel = null,
                unrealEngineLocation = null,
                initialBackgroundIndexingCompleted = null,
                ubtInProgress = null,
                unrealDumbMode = null,
                error = "Rider solution model is unavailable.",
            )
            val unrealModel = unrealModel(solution) ?: return RiderUnrealReadiness(
                frontendDumbMode = frontendDumbMode,
                unrealModelAvailable = false,
                unrealSolution = null,
                uProjectModel = null,
                unrealEngineLocation = null,
                initialBackgroundIndexingCompleted = null,
                ubtInProgress = null,
                unrealDumbMode = null,
                error = "Rider UnrealModel extension is unavailable.",
            )
            RiderUnrealReadiness(
                frontendDumbMode = frontendDumbMode,
                unrealModelAvailable = true,
                unrealSolution = optBoolean(unrealModel, "isUnrealSolution"),
                uProjectModel = optBoolean(unrealModel, "isUProjectModel"),
                unrealEngineLocation = optString(unrealModel, "getUnrealEngineLocation"),
                initialBackgroundIndexingCompleted = optBoolean(unrealModel, "isInitialBackgroundIndexingCompleted"),
                ubtInProgress = optBoolean(unrealModel, "isUbtInProgress"),
                unrealDumbMode = optBoolean(unrealModel, "isInDumbMode"),
                error = null,
            )
        }.getOrElse { t ->
            RiderUnrealReadiness(
                frontendDumbMode = frontendDumbMode,
                unrealModelAvailable = false,
                unrealSolution = null,
                uProjectModel = null,
                unrealEngineLocation = null,
                initialBackgroundIndexingCompleted = null,
                ubtInProgress = null,
                unrealDumbMode = null,
                error = t.message ?: t.javaClass.name,
            )
        }
    }

    private fun solution(): Any? {
        val facade = Class.forName("com.jetbrains.rider.projectView.SolutionHostExtensionsKt")
        return facade.getMethod("getSolution", Project::class.java).invoke(null, project)
    }

    private fun unrealModel(solution: Any): Any? {
        val facade = Class.forName("com.jetbrains.rd.ide.model.UnrealModel_PregeneratedKt")
        return facade.getMethod("getUnrealModel", solution.javaClass).invoke(null, solution)
    }

    private fun optBoolean(model: Any, getterName: String): Boolean? {
        return optValue(model, getterName) as? Boolean
    }

    private fun optString(model: Any, getterName: String): String? {
        return optValue(model, getterName) as? String
    }

    private fun optValue(model: Any, getterName: String): Any? {
        val property = model.javaClass.getMethod(getterName).invoke(model) ?: return null
        return property.javaClass.getMethod("getValueOrNull").invoke(property)
    }
}
