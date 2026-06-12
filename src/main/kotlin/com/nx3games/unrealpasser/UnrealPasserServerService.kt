package com.nx3games.unrealpasser

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.nx3games.unrealpasser.core.RiderUnrealStatusProbe
import com.nx3games.unrealpasser.http.SerenaHttpServer
import com.nx3games.unrealpasser.symbols.FileBasedSymbolProvider
import com.nx3games.unrealpasser.symbols.ReSharperBackendRpcBridge
import com.nx3games.unrealpasser.symbols.ReSharperSymbolProvider
import com.nx3games.unrealpasser.unreal.UnrealProjectIndex
import java.util.concurrent.atomic.AtomicBoolean

@Service(Service.Level.PROJECT)
class UnrealPasserServerService(private val project: Project) : Disposable {
    private val started = AtomicBoolean(false)
    private var server: SerenaHttpServer? = null

    fun start() {
        if (!started.compareAndSet(false, true)) return

        val projectRoot = project.basePath
        if (projectRoot == null) {
            started.set(false)
            thisLogger().warn("UnrealPasser cannot start because project.basePath is null")
            return
        }

        try {
            val diagnosticFallback = java.lang.Boolean.getBoolean("unrealpasser.diagnosticFallback")
            var backendStatus: (() -> String)? = null
            var backendOperation: ((String, Map<String, Any?>) -> Map<String, Any?>)? = null
            val provider = if (diagnosticFallback) {
                FileBasedSymbolProvider(projectRoot)
            } else {
                val bridge = ReSharperBackendRpcBridge(project)
                backendStatus = bridge::backendStatus
                backendOperation = bridge::executeMap
                ReSharperSymbolProvider(project, bridge)
            }
            val unreal = UnrealProjectIndex(projectRoot, diagnosticFallback, RiderUnrealStatusProbe(project)::read)
            server = SerenaHttpServer(project, projectRoot, provider, unreal, backendStatus, backendOperation).also { it.start() }
            notify("UnrealPasser listening on 127.0.0.1:${server?.port}")
        } catch (t: Throwable) {
            started.set(false)
            thisLogger().warn("Failed to start UnrealPasser", t)
            notify("Failed to start UnrealPasser: ${t.message}", NotificationType.WARNING)
        }
    }

    override fun dispose() {
        server?.stop()
        server = null
        started.set(false)
    }

    private fun notify(message: String, type: NotificationType = NotificationType.INFORMATION) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("UnrealPasser")
            .createNotification(message, type)
            .notify(project)
    }
}
