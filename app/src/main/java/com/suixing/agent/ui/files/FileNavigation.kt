package com.suixing.agent.ui.files

import java.io.File

/** The selected browser root is the first level, independently of the filesystem root. */
internal fun parentFileDirectory(root: File, current: File): File? {
    val rootPath = root.absoluteFile.toPath().normalize()
    val currentPath = current.absoluteFile.toPath().normalize()
    if (currentPath == rootPath || !currentPath.startsWith(rootPath)) return null
    return currentPath.parent?.takeIf { it.startsWith(rootPath) }?.toFile()
}
