package com.github.localstack.s3browser.toolwindow

import com.github.localstack.s3browser.model.S3TreeNode
import com.github.localstack.s3browser.services.S3ClientService
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
import java.awt.dnd.*
import java.io.File
import javax.swing.JPanel
import javax.swing.SwingUtilities
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

        Disposer.register(this, treeModel)
    }

    private fun setupTree() {
        tree.apply {
            cellRenderer = S3TreeCellRenderer()
            isRootVisible = true
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
        val (bucketName, prefix) = when (targetNode) {
            is S3TreeNode.Bucket -> Pair(targetNode.name, "")
            is S3TreeNode.Folder -> Pair(targetNode.bucketName, targetNode.fullPrefix)
            is S3TreeNode.S3Object -> {
                val parentPrefix = targetNode.key.substringBeforeLast("/", "")
                Pair(targetNode.bucketName, if (parentPrefix.isEmpty()) "" else "$parentPrefix/")
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
                    uploadFileRecursively(s3Service, bucketName, prefix, file)
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

    private fun uploadFileRecursively(s3Service: S3ClientService, bucketName: String, prefix: String, file: File) {
        if (file.isDirectory) {
            val newPrefix = "$prefix${file.name}/"
            s3Service.createFolder(bucketName, newPrefix, project)
            file.listFiles()?.forEach { child ->
                uploadFileRecursively(s3Service, bucketName, newPrefix, child)
            }
        } else {
            val key = "$prefix${file.name}"
            s3Service.putObject(bucketName, key, file.readBytes(), null, project)
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
                val virtualFile = vfs.findOrCreateFile(node.bucketName, node.key, project)

                SwingUtilities.invokeLater {
                    FileEditorManager.getInstance(project).openFile(virtualFile, true)
                }
            } catch (e: Exception) {
                log.warn("Failed to open file: ${node.path}", e)
            }
        }
    }

    fun refresh() {
        treeModel.refresh()
        tree.updateUI()
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
