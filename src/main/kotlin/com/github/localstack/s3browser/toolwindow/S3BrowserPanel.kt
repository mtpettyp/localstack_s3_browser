package com.github.localstack.s3browser.toolwindow

import com.github.localstack.s3browser.model.S3TreeNode
import com.github.localstack.s3browser.services.S3ClientService
import com.github.localstack.s3browser.settings.S3SettingsListener
import com.github.localstack.s3browser.vfs.S3VirtualFileSystem
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.TreeSpeedSearch
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.dnd.*
import java.io.File
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.TransferHandler
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

/**
 * Main panel for the S3 Browser tool window.
 */
class S3BrowserPanel(
    private val project: Project,
    private val toolWindow: ToolWindow
) : JPanel(BorderLayout()), Disposable, DataProvider {

    private val log = Logger.getInstance(S3BrowserPanel::class.java)

    val treeModel = S3TreeModel(project)
    val tree = Tree(treeModel)

    init {
        setupTree()
        setupToolbar()
        setupDragAndDrop()
        setupSettingsListener()

        Disposer.register(this, treeModel)
    }

    private fun setupSettingsListener() {
        val connection = ApplicationManager.getApplication().messageBus.connect(this)
        connection.subscribe(S3SettingsListener.TOPIC, object : S3SettingsListener {
            override fun settingsChanged() {
                SwingUtilities.invokeLater {
                    refresh()
                }
            }
        })
    }

    private fun setupTree() {
        tree.apply {
            cellRenderer = S3TreeCellRenderer()
            isRootVisible = false
            showsRootHandles = true
            selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION

            // Enable speed search
            TreeSpeedSearch.installOn(this, false) { path ->
                (path.lastPathComponent as? S3TreeNode)?.name ?: ""
            }

            // Double-click to open files
            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) {
                    if (e.clickCount == 2) {
                        val path = getPathForLocation(e.x, e.y) ?: return
                        val node = path.lastPathComponent
                        if (node is S3TreeNode.S3Object) {
                            openFile(node)
                        }
                    }
                }
            })

            // Context menu
            addMouseListener(object : PopupHandler() {
                override fun invokePopup(comp: java.awt.Component?, x: Int, y: Int) {
                    val path = getPathForLocation(x, y)
                    if (path != null) {
                        selectionPath = path
                    }
                    showContextMenu(x, y)
                }
            })

            // Enter key to open files
            addKeyListener(object : java.awt.event.KeyAdapter() {
                override fun keyPressed(e: java.awt.event.KeyEvent) {
                    if (e.keyCode == java.awt.event.KeyEvent.VK_ENTER) {
                        val node = selectionPath?.lastPathComponent
                        if (node is S3TreeNode.S3Object) {
                            openFile(node)
                        }
                    } else if (e.keyCode == java.awt.event.KeyEvent.VK_DELETE) {
                        val action = ActionManager.getInstance().getAction("LocalStackS3.Delete")
                        val event = AnActionEvent.createFromAnAction(
                            action,
                            e,
                            ActionPlaces.TOOLWINDOW_CONTENT,
                            createDataContext()
                        )
                        action.actionPerformed(event)
                    } else if (e.keyCode == java.awt.event.KeyEvent.VK_F5) {
                        refresh()
                    } else if (e.keyCode == java.awt.event.KeyEvent.VK_C &&
                               (e.modifiersEx and java.awt.event.InputEvent.META_DOWN_MASK != 0 ||
                                e.modifiersEx and java.awt.event.InputEvent.CTRL_DOWN_MASK != 0)) {
                        // CMD+C (Mac) or CTRL+C (Windows/Linux) to copy files
                        copySelectedToClipboard()
                    }
                }
            })
        }

        val scrollPane = ScrollPaneFactory.createScrollPane(tree)
        add(scrollPane, BorderLayout.CENTER)
    }

    private fun setupToolbar() {
        val actionGroup = ActionManager.getInstance().getAction("LocalStackS3.ToolbarActions") as? ActionGroup
        if (actionGroup != null) {
            val toolbar = ActionManager.getInstance().createActionToolbar(
                ActionPlaces.TOOLWINDOW_CONTENT,
                actionGroup,
                true
            )
            toolbar.targetComponent = this
            add(toolbar.component, BorderLayout.NORTH)
        }
    }

    private fun setupDragAndDrop() {
        // Setup drag source for dragging S3 files out of the tree
        tree.transferHandler = object : TransferHandler() {
            override fun getSourceActions(c: javax.swing.JComponent): Int = COPY

            override fun createTransferable(c: javax.swing.JComponent): Transferable? {
                val selectedNode = getSelectedNode() ?: return null
                return S3FileTransferable(selectedNode, this@S3BrowserPanel)
            }
        }
        tree.dragEnabled = true

        // Setup drop target for dropping files into the tree
        DropTarget(tree, DnDConstants.ACTION_COPY, object : DropTargetListener {
            override fun dragEnter(dtde: DropTargetDragEvent) {
                if (isFileDrag(dtde)) {
                    dtde.acceptDrag(DnDConstants.ACTION_COPY)
                } else {
                    dtde.rejectDrag()
                }
            }

            override fun dragOver(dtde: DropTargetDragEvent) {
                if (isFileDrag(dtde)) {
                    val path = tree.getPathForLocation(dtde.location.x, dtde.location.y)
                    if (path != null) {
                        tree.selectionPath = path
                    }
                    dtde.acceptDrag(DnDConstants.ACTION_COPY)
                } else {
                    dtde.rejectDrag()
                }
            }

            override fun dropActionChanged(dtde: DropTargetDragEvent) {}

            override fun dragExit(dte: DropTargetEvent) {}

            override fun drop(dtde: DropTargetDropEvent) {
                try {
                    if (!isFileDrop(dtde)) {
                        dtde.rejectDrop()
                        return
                    }

                    dtde.acceptDrop(DnDConstants.ACTION_COPY)

                    val transferable = dtde.transferable
                    val files = transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>

                    if (files.isNullOrEmpty()) {
                        dtde.dropComplete(false)
                        return
                    }

                    val dropPath = tree.getPathForLocation(dtde.location.x, dtde.location.y)
                    val targetNode = dropPath?.lastPathComponent as? S3TreeNode

                    handleFileDrop(files.filterIsInstance<File>(), targetNode)
                    dtde.dropComplete(true)

                } catch (e: Exception) {
                    log.warn("Drop failed", e)
                    dtde.dropComplete(false)
                }
            }

            private fun isFileDrag(dtde: DropTargetDragEvent): Boolean {
                return dtde.transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
            }

            private fun isFileDrop(dtde: DropTargetDropEvent): Boolean {
                return dtde.transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
            }
        })
    }

    private fun handleFileDrop(files: List<File>, targetNode: S3TreeNode?) {
        val (instanceId, bucketName, prefix) = when (targetNode) {
            is S3TreeNode.Bucket -> Triple(targetNode.instanceId, targetNode.name, "")
            is S3TreeNode.Folder -> Triple(targetNode.instanceId, targetNode.bucketName, targetNode.fullPrefix)
            is S3TreeNode.S3Object -> {
                val parentPrefix = targetNode.key.substringBeforeLast("/", "")
                Triple(targetNode.instanceId, targetNode.bucketName, if (parentPrefix.isEmpty()) "" else "$parentPrefix/")
            }
            else -> {
                log.warn("Cannot drop files on this node type: ${targetNode?.javaClass?.simpleName}")
                return
            }
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            val s3Service = S3ClientService.getInstance()
            try {
                for (file in files) {
                    uploadFileRecursively(s3Service, instanceId, bucketName, prefix, file)
                }

                SwingUtilities.invokeLater {
                    when (targetNode) {
                        is S3TreeNode.Bucket -> treeModel.refreshNode(targetNode)
                        is S3TreeNode.Folder -> treeModel.refreshNode(targetNode)
                        is S3TreeNode.S3Object -> {
                            // Refresh parent folder
                            val parentPath = tree.selectionPath?.parentPath
                            val parentNode = parentPath?.lastPathComponent as? S3TreeNode
                            if (parentNode != null) {
                                treeModel.refreshNode(parentNode)
                            }
                        }
                        else -> refresh()
                    }
                }
            } catch (e: Exception) {
                log.warn("Failed to upload files", e)
            }
        }
    }

    private fun uploadFileRecursively(s3Service: S3ClientService, instanceId: String, bucketName: String, prefix: String, file: File) {
        if (file.isDirectory) {
            val newPrefix = "$prefix${file.name}/"
            s3Service.createFolder(instanceId, bucketName, newPrefix)
            file.listFiles()?.forEach { child ->
                uploadFileRecursively(s3Service, instanceId, bucketName, newPrefix, child)
            }
        } else {
            val key = "$prefix${file.name}"
            s3Service.putObject(instanceId, bucketName, key, file.readBytes(), null)
        }
    }

    private fun showContextMenu(x: Int, y: Int) {
        val actionGroup = ActionManager.getInstance().getAction("LocalStackS3.ContextMenu") as? ActionGroup
        if (actionGroup != null) {
            val popupMenu = ActionManager.getInstance().createActionPopupMenu(
                ActionPlaces.TOOLWINDOW_CONTENT,
                actionGroup
            )
            popupMenu.component.show(tree, x, y)
        }
    }

    private fun openFile(node: S3TreeNode.S3Object) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val vfs = S3VirtualFileSystem.getInstance()
                val virtualFile = vfs.findOrCreateFile(node.instanceId, node.bucketName, node.key)

                SwingUtilities.invokeLater {
                    FileEditorManager.getInstance(project).openFile(virtualFile, true)
                }
            } catch (e: Exception) {
                log.warn("Failed to open file: ${node.path}", e)
            }
        }
    }

    private fun copySelectedToClipboard() {
        val node = getSelectedNode() ?: return

        // Only allow copying for files, folders, and buckets
        if (node !is S3TreeNode.S3Object && node !is S3TreeNode.Folder && node !is S3TreeNode.Bucket) {
            return
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val transferable = S3FileTransferable(node, this)
                SwingUtilities.invokeLater {
                    java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(transferable, null)
                    log.info("Copied ${node.name} to clipboard")
                }
            } catch (e: Exception) {
                log.warn("Failed to copy to clipboard: ${node.path}", e)
            }
        }
    }

    fun refresh() {
        // Save expanded paths before refresh
        val expandedPaths = getExpandedNodePaths()

        treeModel.refresh()
        tree.updateUI()

        // Restore expanded paths after a short delay to allow tree to rebuild
        if (expandedPaths.isNotEmpty()) {
            ApplicationManager.getApplication().executeOnPooledThread {
                // Wait a bit for the tree to load initial data
                Thread.sleep(100)
                SwingUtilities.invokeLater {
                    restoreExpandedPaths(expandedPaths)
                }
            }
        }
    }

    private fun getExpandedNodePaths(): Set<String> {
        val expandedPaths = mutableSetOf<String>()
        val rowCount = tree.rowCount
        for (row in 0 until rowCount) {
            val treePath = tree.getPathForRow(row)
            if (tree.isExpanded(treePath)) {
                val node = treePath.lastPathComponent as? S3TreeNode
                if (node != null) {
                    expandedPaths.add(node.path)
                }
            }
        }
        return expandedPaths
    }

    private fun restoreExpandedPaths(expandedPaths: Set<String>) {
        expandPathsRecursively(expandedPaths, tree.getPathForRow(0) ?: return)
    }

    private fun expandPathsRecursively(expandedPaths: Set<String>, treePath: TreePath) {
        val node = treePath.lastPathComponent as? S3TreeNode ?: return

        if (node.path in expandedPaths) {
            tree.expandPath(treePath)

            // After expanding, schedule checking children
            ApplicationManager.getApplication().executeOnPooledThread {
                Thread.sleep(50)
                SwingUtilities.invokeLater {
                    val childCount = treeModel.getChildCount(node)
                    for (i in 0 until childCount) {
                        val child = treeModel.getChild(node, i)
                        if (child is S3TreeNode && child !is S3TreeNode.Loading && child !is S3TreeNode.Error) {
                            val childPath = treePath.pathByAddingChild(child)
                            expandPathsRecursively(expandedPaths, childPath)
                        }
                    }
                }
            }
        }
    }

    fun getSelectedNode(): S3TreeNode? {
        return tree.selectionPath?.lastPathComponent as? S3TreeNode
    }

    fun getSelectedPath(): TreePath? {
        return tree.selectionPath
    }

    fun createDataContext(): DataContext {
        return DataContext { dataId ->
            getData(dataId)
        }
    }

    override fun getData(dataId: String): Any? {
        return when {
            CommonDataKeys.PROJECT.`is`(dataId) -> project
            PlatformDataKeys.TOOL_WINDOW.`is`(dataId) -> toolWindow
            S3_TREE_NODE.`is`(dataId) -> getSelectedNode()
            S3_BROWSER_PANEL.`is`(dataId) -> this
            else -> null
        }
    }

    override fun dispose() {
        // Cleanup handled by Disposer
    }

    companion object {
        val S3_TREE_NODE = DataKey.create<S3TreeNode>("S3_TREE_NODE")
        val S3_BROWSER_PANEL = DataKey.create<S3BrowserPanel>("S3_BROWSER_PANEL")
    }
}
