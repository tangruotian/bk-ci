package com.tencent.devops.environment.service

import com.tencent.devops.common.api.pojo.OS
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.StreamingOutput
import org.springframework.stereotype.Service

/**
 * 创作环境相关
 */
@Service
class CreateEnvService {
    fun fetchUserWorkspaceId(projectId: String, userId: String): List<String> {
        return emptyList()
    }

    fun getWorkspaceInfo(projectId: String, workspaceId: String): WorkspaceInfo {
        return WorkspaceInfo(
            zoneName = null,
            os = OS.WINDOWS
        )
    }

    fun getWorkspaceDisplayName(userId: String, projectId: String, workspaceId: String?): String? {
        return null
    }

    fun genCreateNodeInstallScript(
        token: String,
        deviceId: String,
        userId: String
    ): Response {
        return Response.ok(StreamingOutput { output ->
            output.write("".toByteArray())
            output.flush()
        }, MediaType.APPLICATION_OCTET_STREAM_TYPE)
            .header("content-disposition", "attachment; filename = ")
            .build()
    }
}

data class WorkspaceInfo(
    val zoneName: String?,
    val os: OS
)