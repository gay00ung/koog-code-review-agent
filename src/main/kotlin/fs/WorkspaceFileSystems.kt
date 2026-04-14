package net.lateinit.fs

import ai.koog.rag.base.files.FileMetadata
import ai.koog.rag.base.files.FileSystemProvider
import ai.koog.rag.base.files.JVMFileSystemProvider
import kotlinx.io.Sink
import kotlinx.io.Source
import java.nio.file.NoSuchFileException
import java.nio.file.Path

/**
 * Koog 파일 도구가 생성물과 IDE 메타데이터를 읽지 않도록 작업 공간 파일 시스템을 제한합니다.
 *
 * 이 파일 시스템은 작업 루트 밖의 경로를 숨기고, `.git`, `.gradle`, `.idea`, `build` 디렉터리와
 * `jar`, `class`, `log` 같은 대용량 또는 바이너리 성격의 파일을 기본적으로 제외합니다.
 *
 * @param workspaceRoot 현재 프로젝트의 작업 루트 절대 경로입니다.
 * @return 안전한 탐색 범위만 노출하는 읽기 전용 파일 시스템입니다.
 */
fun createWorkspaceFileSystem(workspaceRoot: Path): FileSystemProvider.ReadOnly<Path> {
    return RestrictedWorkspaceFileSystemProvider(
        delegate = JVMFileSystemProvider.ReadOnly,
        workspaceRoot = workspaceRoot,
    )
}

/**
 * Koog 수정 도구가 `README.md`만 바꿀 수 있도록 제한된 쓰기 파일 시스템을 만듭니다.
 *
 * 읽기 전용 탐색용 파일 시스템과 분리해서, 수정 도구는 이 provider를 통해서만 파일에 접근합니다.
 * 현재 단계에서는 학습 범위를 좁히기 위해 작업 루트의 `README.md` 한 파일만 쓰기 대상으로 허용합니다.
 *
 * @param workspaceRoot 현재 프로젝트의 작업 루트 절대 경로입니다.
 * @return `README.md`에 대해서만 읽기/쓰기 작업을 허용하는 읽기-쓰기 파일 시스템입니다.
 */
fun createWritableWorkspaceFileSystem(workspaceRoot: Path): FileSystemProvider.ReadWrite<Path> {
    return RestrictedWritableWorkspaceFileSystemProvider(
        delegate = JVMFileSystemProvider.ReadWrite,
        workspaceRoot = workspaceRoot,
    )
}

/**
 * 특정 경로가 에이전트 도구에 노출되어도 되는 작업 공간 경로인지 판별합니다.
 *
 * @param path 검사할 실제 파일 시스템 경로입니다.
 * @param workspaceRoot 현재 프로젝트의 작업 루트입니다.
 * @return 작업 공간 내부이면서 생성물/메타데이터 경로가 아니면 `true`입니다.
 */
private fun isAllowedWorkspacePath(path: Path, workspaceRoot: Path): Boolean {
    val normalizedPath = path.toAbsolutePath().normalize()

    if (!normalizedPath.startsWith(workspaceRoot)) {
        return false
    }

    if (normalizedPath == workspaceRoot) {
        return true
    }

    val relativePath = workspaceRoot.relativize(normalizedPath)
    val segments = relativePath.map { it.toString() }
    val excludedDirectories = setOf(".git", ".gradle", ".idea", "build")

    if (segments.any { it in excludedDirectories }) {
        return false
    }

    val fileName = normalizedPath.fileName?.toString().orEmpty()
    val excludedExtensions = setOf("jar", "class", "log")
    val extension = fileName.substringAfterLast('.', missingDelimiterValue = "")

    return extension !in excludedExtensions
}

/**
 * 특정 경로가 수정 가능한 경로인지 판별합니다.
 *
 * 현재는 학습용 안전장치로 작업 루트의 `README.md`만 수정 대상으로 허용합니다.
 *
 * @param path 검사할 실제 파일 시스템 경로입니다.
 * @param workspaceRoot 현재 프로젝트의 작업 루트입니다.
 * @return 경로가 작업 루트의 `README.md`와 정확히 일치하면 `true`입니다.
 */
private fun isAllowedWritablePath(path: Path, workspaceRoot: Path): Boolean {
    val normalizedPath = path.toAbsolutePath().normalize()
    val allowedFile = workspaceRoot.resolve("README.md").toAbsolutePath().normalize()
    return normalizedPath == allowedFile
}

/**
 * 생성물 디렉터리와 바이너리 파일을 숨기는 작업 공간 전용 읽기 전용 파일 시스템입니다.
 *
 * Koog의 파일 도구는 이 래퍼를 통해 경로를 탐색하므로, 허용되지 않은 경로는 목록에서 빠지고
 * 직접 읽으려고 해도 접근할 수 없습니다.
 *
 * @property delegate 실제 파일 시스템 작업을 수행하는 기반 provider입니다.
 * @property workspaceRoot 현재 프로젝트의 작업 루트 절대 경로입니다.
 */
private class RestrictedWorkspaceFileSystemProvider(
    private val delegate: FileSystemProvider.ReadOnly<Path>,
    private val workspaceRoot: Path,
) : FileSystemProvider.ReadOnly<Path> {

    override fun toAbsolutePathString(path: Path): String = delegate.toAbsolutePathString(path)

    override fun fromAbsolutePathString(path: String): Path = delegate.fromAbsolutePathString(path)

    override fun joinPath(base: Path, vararg parts: String): Path = delegate.joinPath(base, *parts)

    override fun name(path: Path): String = delegate.name(path)

    override fun extension(path: Path): String = delegate.extension(path)

    override suspend fun metadata(path: Path): FileMetadata {
        ensureAllowed(path)
        return requireNotNull(delegate.metadata(path)) {
            "Metadata is unavailable for $path"
        }
    }

    override suspend fun getFileContentType(path: Path): FileMetadata.FileContentType {
        ensureAllowed(path)
        return delegate.getFileContentType(path)
    }

    override suspend fun list(directory: Path): List<Path> {
        if (!isAllowedWorkspacePath(directory, workspaceRoot)) {
            return emptyList()
        }

        return delegate.list(directory)
            .filter { isAllowedWorkspacePath(it, workspaceRoot) }
    }

    override fun parent(path: Path): Path =
        requireNotNull(delegate.parent(path)) { "Parent path is unavailable for $path" }

    override fun relativize(root: Path, path: Path): String =
        requireNotNull(delegate.relativize(root, path)) { "Relative path is unavailable for $path" }

    override suspend fun exists(path: Path): Boolean {
        return isAllowedWorkspacePath(path, workspaceRoot) && delegate.exists(path)
    }

    override suspend fun readBytes(path: Path): ByteArray {
        ensureAllowed(path)
        return delegate.readBytes(path)
    }

    override suspend fun inputStream(path: Path): Source {
        ensureAllowed(path)
        return delegate.inputStream(path)
    }

    override suspend fun size(path: Path): Long {
        ensureAllowed(path)
        return delegate.size(path)
    }

    /**
     * 허용되지 않은 경로 접근을 조기에 차단합니다.
     *
     * @param path 실제로 읽으려는 대상 경로입니다.
     */
    private fun ensureAllowed(path: Path) {
        if (!isAllowedWorkspacePath(path, workspaceRoot)) {
            throw NoSuchFileException(path.toString())
        }
    }
}

/**
 * 수정 도구 전용으로 `README.md` 한 파일만 읽고 쓸 수 있게 제한하는 파일 시스템입니다.
 *
 * `EditFileTool`은 읽기와 쓰기 메서드를 모두 사용하므로, 이 래퍼는 `ReadWrite` 전체를 구현합니다.
 * 허용되지 않은 경로에 대해서는 목록에서 숨기고, 직접 읽거나 쓰려 하면 즉시 예외를 던집니다.
 *
 * @property delegate 실제 읽기/쓰기 작업을 수행하는 기반 provider입니다.
 * @property workspaceRoot 현재 프로젝트의 작업 루트 절대 경로입니다.
 */
private class RestrictedWritableWorkspaceFileSystemProvider(
    private val delegate: FileSystemProvider.ReadWrite<Path>,
    private val workspaceRoot: Path,
) : FileSystemProvider.ReadWrite<Path> {

    override fun toAbsolutePathString(path: Path): String = delegate.toAbsolutePathString(path)

    override fun fromAbsolutePathString(path: String): Path = delegate.fromAbsolutePathString(path)

    override fun joinPath(base: Path, vararg parts: String): Path = delegate.joinPath(base, *parts)

    override fun name(path: Path): String = delegate.name(path)

    override fun extension(path: Path): String = delegate.extension(path)

    override suspend fun metadata(path: Path): FileMetadata {
        ensureAllowed(path)
        return requireNotNull(delegate.metadata(path)) {
            "Metadata is unavailable for $path"
        }
    }

    override suspend fun getFileContentType(path: Path): FileMetadata.FileContentType {
        ensureAllowed(path)
        return delegate.getFileContentType(path)
    }

    override suspend fun list(directory: Path): List<Path> {
        val normalizedDirectory = directory.toAbsolutePath().normalize()

        return when {
            normalizedDirectory == workspaceRoot ->
                delegate.list(directory).filter { isAllowedWritablePath(it, workspaceRoot) }

            isAllowedWritablePath(directory, workspaceRoot) -> emptyList()
            else -> emptyList()
        }
    }

    override fun parent(path: Path): Path =
        requireNotNull(delegate.parent(path)) { "Parent path is unavailable for $path" }

    override fun relativize(root: Path, path: Path): String =
        requireNotNull(delegate.relativize(root, path)) { "Relative path is unavailable for $path" }

    override suspend fun exists(path: Path): Boolean {
        return isAllowedWritablePath(path, workspaceRoot) && delegate.exists(path)
    }

    override suspend fun readBytes(path: Path): ByteArray {
        ensureAllowed(path)
        return delegate.readBytes(path)
    }

    override suspend fun inputStream(path: Path): Source {
        ensureAllowed(path)
        return delegate.inputStream(path)
    }

    override suspend fun size(path: Path): Long {
        ensureAllowed(path)
        return delegate.size(path)
    }

    override suspend fun create(path: Path, type: FileMetadata.FileType) {
        ensureAllowed(path)
        delegate.create(path, type)
    }

    override suspend fun move(source: Path, target: Path) {
        ensureAllowed(source)
        ensureAllowed(target)
        delegate.move(source, target)
    }

    override suspend fun copy(source: Path, target: Path) {
        ensureAllowed(source)
        ensureAllowed(target)
        delegate.copy(source, target)
    }

    override suspend fun writeBytes(path: Path, data: ByteArray) {
        ensureAllowed(path)
        delegate.writeBytes(path, data)
    }

    override suspend fun outputStream(path: Path, append: Boolean): Sink {
        ensureAllowed(path)
        return delegate.outputStream(path, append)
    }

    override suspend fun delete(path: Path) {
        ensureAllowed(path)
        delegate.delete(path)
    }

    /**
     * 허용되지 않은 파일 수정 또는 읽기를 조기에 차단합니다.
     *
     * @param path 실제로 접근하려는 대상 경로입니다.
     */
    private fun ensureAllowed(path: Path) {
        if (!isAllowedWritablePath(path, workspaceRoot)) {
            throw NoSuchFileException(path.toString())
        }
    }
}
