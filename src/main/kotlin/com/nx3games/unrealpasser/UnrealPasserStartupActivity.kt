package com.nx3games.unrealpasser

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.components.service

class UnrealPasserStartupActivity : ProjectActivity, DumbAware {
    override suspend fun execute(project: Project) {
        thisLogger().info("UnrealPasser startup activity executing for ${project.name} at ${project.basePath}")
        project.service<UnrealPasserServerService>().start()
        thisLogger().info("UnrealPasser startup activity completed for ${project.name}")
    }
}
