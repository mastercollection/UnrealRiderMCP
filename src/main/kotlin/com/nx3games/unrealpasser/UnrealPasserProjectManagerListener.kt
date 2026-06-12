package com.nx3games.unrealpasser

import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManagerListener

class UnrealPasserProjectManagerListener : ProjectManagerListener {
    override fun projectOpened(project: Project) {
        thisLogger().info("UnrealPasser projectOpened listener executing for ${project.name} at ${project.basePath}")
        project.service<UnrealPasserServerService>().start()
    }
}
