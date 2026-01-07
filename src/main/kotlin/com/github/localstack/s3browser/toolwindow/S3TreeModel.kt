package com.github.localstack.s3browser.toolwindow

import com.github.localstack.s3browser.model.S3TreeNode
import com.github.localstack.s3browser.services.S3ClientService
import com.github.localstack.s3browser.settings.S3BrowserProjectSettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.util.concurrent.ConcurrentHashMap
import javax.swing.SwingUtilities
import javax.swing.event.TreeModelEvent
import javax.swing.event.TreeModelListener
import javax.swing.tree.TreeModel
import javax.swing.tree.TreePath

/**
 * Tree model for the S3 browser that supports lazy loading of children.
 */
class S3TreeModel(
    private val project: Project
) : TreeModel, Disposable {

    private val log = Logger.getInstance(S3TreeModel::class.java)
    private val listeners = mutableListOf<TreeModelListener>()
    private val childrenCache = ConcurrentHashMap<String, List<S3TreeNode>>()
    private val loadingNodes = ConcurrentHashMap.newKeySet<String>()

    private var rootNode: S3TreeNode.Root = S3TreeNode.Root(
        S3BrowserProjectSettings.getInstance(project).getEffectiveEndpoint()
    )

    override fun getRoot(): Any = rootNode

    override fun getChild(parent: Any?, index: Int): Any {
        val node = parent as? S3TreeNode ?: return S3TreeNode.Error("Invalid parent")
        val children = getChildren(node)
        return if (index < children.size) children[index] else S3TreeNode.Error("Index out of bounds")
    }

    override fun getChildCount(parent: Any?): Int {
        val node = parent as? S3TreeNode ?: return 0
        return getChildren(node).size
    }

    override fun isLeaf(node: Any?): Boolean {
        return when (node) {
            is S3TreeNode.S3Object -> true
            is S3TreeNode.Loading -> true
            is S3TreeNode.Error -> true
            else -> false
        }
    }

    override fun valueForPathChanged(path: TreePath?, newValue: Any?) {
        // Not used for now - could support inline renaming
    }

    override fun getIndexOfChild(parent: Any?, child: Any?): Int {
        val node = parent as? S3TreeNode ?: return -1
        return getChildren(node).indexOf(child)
    }

    override fun addTreeModelListener(l: TreeModelListener?) {
        l?.let { listeners.add(it) }
    }

    override fun removeTreeModelListener(l: TreeModelListener?) {
        l?.let { listeners.remove(it) }
    }

    /**
     * Gets children for a node, loading them if necessary.
     */
    fun getChildren(node: S3TreeNode): List<S3TreeNode> {
        val cacheKey = node.path

        // Return cached children if available
        childrenCache[cacheKey]?.let { return it }

        // If already loading, return a loading placeholder
        if (loadingNodes.contains(cacheKey)) {
            return listOf(S3TreeNode.Loading(node))
        }

        // Start loading children in background
        loadingNodes.add(cacheKey)
        loadChildrenAsync(node)

        // Return loading placeholder while loading
        return listOf(S3TreeNode.Loading(node))
    }

    /**
     * Loads children asynchronously and updates the model.
     */
    private fun loadChildrenAsync(node: S3TreeNode) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val children = loadChildren(node)
                childrenCache[node.path] = children
                loadingNodes.remove(node.path)

                SwingUtilities.invokeLater {
                    fireTreeStructureChanged(node)
                }
            } catch (e: Exception) {
                log.warn("Failed to load children for ${node.path}", e)
                childrenCache[node.path] = listOf(S3TreeNode.Error(e.message ?: "Unknown error", node))
                loadingNodes.remove(node.path)

                SwingUtilities.invokeLater {
                    fireTreeStructureChanged(node)
                }
            }
        }
    }

    /**
     * Loads children synchronously.
     */
    private fun loadChildren(node: S3TreeNode): List<S3TreeNode> {
        val s3Service = S3ClientService.getInstance()

        return when (node) {
            is S3TreeNode.Root -> {
                s3Service.listBuckets(project).sortedBy { it.name }
            }

            is S3TreeNode.Bucket -> {
                val (folders, objects) = s3Service.listObjects(node.name, "", project)
                (folders.sortedBy { it.name } + objects.sortedBy { it.name })
            }

            is S3TreeNode.Folder -> {
                val (folders, objects) = s3Service.listObjects(node.bucketName, node.fullPrefix, project)
                (folders.sortedBy { it.name } + objects.sortedBy { it.name })
            }

            else -> emptyList()
        }
    }

    /**
     * Refreshes the entire tree.
     */
    fun refresh() {
        childrenCache.clear()
        loadingNodes.clear()
        rootNode = S3TreeNode.Root(
            S3BrowserProjectSettings.getInstance(project).getEffectiveEndpoint()
        )
        fireTreeStructureChanged(rootNode)
    }

    /**
     * Refreshes a specific node.
     */
    fun refreshNode(node: S3TreeNode) {
        childrenCache.remove(node.path)
        loadingNodes.remove(node.path)
        fireTreeStructureChanged(node)
    }

    /**
     * Invalidates cached children for a node, causing them to be reloaded on next access.
     */
    fun invalidateNode(node: S3TreeNode) {
        childrenCache.remove(node.path)
        loadingNodes.remove(node.path)
    }

    /**
     * Finds the tree path for a given S3 path.
     */
    fun findPath(s3Path: String): TreePath? {
        val parts = s3Path.split("/").filter { it.isNotEmpty() }
        if (parts.isEmpty()) return TreePath(rootNode)

        val pathElements = mutableListOf<S3TreeNode>(rootNode)
        var currentNode: S3TreeNode = rootNode

        for ((index, part) in parts.withIndex()) {
            val children = childrenCache[currentNode.path] ?: return null
            val child = children.find { it.name == part } ?: return null
            pathElements.add(child)
            currentNode = child
        }

        return TreePath(pathElements.toTypedArray())
    }

    private fun fireTreeStructureChanged(node: S3TreeNode) {
        val path = buildPathToNode(node)
        val event = TreeModelEvent(this, path)
        for (listener in listeners) {
            listener.treeStructureChanged(event)
        }
    }

    private fun buildPathToNode(node: S3TreeNode): TreePath {
        val path = mutableListOf<S3TreeNode>()

        when (node) {
            is S3TreeNode.Root -> {
                path.add(node)
            }
            is S3TreeNode.Bucket -> {
                path.add(rootNode)
                path.add(node)
            }
            is S3TreeNode.Folder -> {
                path.add(rootNode)
                // Find the bucket
                val bucket = childrenCache[rootNode.path]?.find {
                    it is S3TreeNode.Bucket && it.name == node.bucketName
                }
                if (bucket != null) {
                    path.add(bucket)
                    // Build path through folders
                    val prefixParts = node.prefix.trimEnd('/').split("/")
                    var currentPrefix = ""
                    for (part in prefixParts.dropLast(1)) {
                        currentPrefix += "$part/"
                        val folder = S3TreeNode.Folder(part, node.bucketName, currentPrefix)
                        path.add(folder)
                    }
                    path.add(node)
                }
            }
            is S3TreeNode.S3Object -> {
                path.add(rootNode)
                val bucket = childrenCache[rootNode.path]?.find {
                    it is S3TreeNode.Bucket && it.name == node.bucketName
                }
                if (bucket != null) {
                    path.add(bucket)
                    // Build path through folders
                    val keyParts = node.key.split("/").dropLast(1)
                    var currentPrefix = ""
                    for (part in keyParts) {
                        currentPrefix += "$part/"
                        val folder = S3TreeNode.Folder(part, node.bucketName, currentPrefix)
                        path.add(folder)
                    }
                    path.add(node)
                }
            }
            else -> {
                path.add(rootNode)
            }
        }

        return TreePath(path.toTypedArray())
    }

    override fun dispose() {
        childrenCache.clear()
        loadingNodes.clear()
        listeners.clear()
    }
}
