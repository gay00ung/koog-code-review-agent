package net.lateinit.tooling

import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.ext.tool.file.EditFileTool
import ai.koog.agents.ext.tool.file.ListDirectoryTool
import ai.koog.agents.ext.tool.file.ReadFileTool
import ai.koog.agents.ext.tool.search.RegexSearchTool
import ai.koog.agents.ext.tool.shell.ExecuteShellCommandTool
import ai.koog.agents.ext.tool.shell.JvmShellCommandExecutor
import ai.koog.agents.ext.tool.shell.PrintShellCommandConfirmationHandler
import java.nio.file.Path
import net.lateinit.fs.createWorkspaceFileSystem
import net.lateinit.fs.createWritableWorkspaceFileSystem

/**
 * 현재 프로젝트에서 사용할 Koog 도구 레지스트리를 구성합니다.
 *
 * 읽기용 도구는 생성물 디렉터리를 숨기는 읽기 전용 파일 시스템을 사용하고,
 * 수정 도구는 `README.md`만 바꿀 수 있는 제한된 쓰기 파일 시스템을 사용합니다.
 *
 * @param workspaceRoot 현재 프로젝트의 작업 루트 절대 경로입니다.
 * @return 프로젝트 분석과 제한된 수정 작업에 사용할 도구 레지스트리입니다.
 */
fun createProjectToolRegistry(workspaceRoot: Path): ToolRegistry {
    val readFileSystem = createWorkspaceFileSystem(workspaceRoot)
    val writeFileSystem = createWritableWorkspaceFileSystem(workspaceRoot)

    return ToolRegistry {
        tool(ListDirectoryTool(readFileSystem))
        tool(ReadFileTool(readFileSystem))
        tool(RegexSearchTool(readFileSystem))
        tool(EditFileTool(writeFileSystem))
        tool(
            ExecuteShellCommandTool(
                executor = JvmShellCommandExecutor(),
                confirmationHandler = PrintShellCommandConfirmationHandler(),
            )
        )
    }
}
