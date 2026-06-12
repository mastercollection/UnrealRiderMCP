package com.nx3games.unrealpasser.protocol

import com.intellij.openapi.client.ClientProjectSession
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.jetbrains.rd.ide.model.Solution
import com.jetbrains.rd.protocol.SolutionExtListener
import com.jetbrains.rd.util.lifetime.Lifetime

class UnrealPasserProtocolListener : SolutionExtListener<UnrealPasserBackendModel> {
    override fun extensionCreated(lifetime: Lifetime, session: ClientProjectSession, model: UnrealPasserBackendModel) {
        thisLogger().info("UnrealPasser ReSharper backend RD model connected")
        UnrealPasserBackendConnection.bind(lifetime, model)
    }
}

object UnrealPasserBackendConnection {
    @Volatile
    private var model: UnrealPasserBackendModel? = null

    fun bind(lifetime: Lifetime, model: UnrealPasserBackendModel) {
        this.model = model
        lifetime.onTermination {
            if (this.model === model) {
                this.model = null
            }
        }
    }

    fun current(): UnrealPasserBackendModel? = model

    fun current(project: Project): UnrealPasserBackendModel? {
        model?.let { return it }
        return resolveFromProject(project)?.also { model = it }
    }

    fun isConnected(): Boolean = model != null

    fun isConnected(project: Project): Boolean = current(project) != null

    private fun resolveFromProject(project: Project): UnrealPasserBackendModel? {
        return runCatching {
            val facade = Class.forName("com.jetbrains.rider.projectView.SolutionHostExtensionsKt")
            val solution = facade.getMethod("getSolution", Project::class.java).invoke(null, project) as? Solution
                ?: return null
            solution.getUnrealPasserBackendModel()
        }.getOrNull()
    }
}
