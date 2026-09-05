package dev.timbrinded.prompttemplates.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.EditorTextField
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import dev.timbrinded.prompttemplates.PromptTemplatesProjectService
import dev.timbrinded.prompttemplates.core.DiagnosticSeverity
import dev.timbrinded.prompttemplates.core.RepositoryResult
import dev.timbrinded.prompttemplates.core.StoredTemplate
import dev.timbrinded.prompttemplates.core.TemplateDiagnostic
import dev.timbrinded.prompttemplates.core.TemplateSearch
import dev.timbrinded.prompttemplates.core.referencedUserVariables
import dev.timbrinded.prompttemplates.destination.DestinationResult
import dev.timbrinded.prompttemplates.invocation.InvocationPresentation
import dev.timbrinded.prompttemplates.settings.PromptTemplatesSettings
import dev.timbrinded.prompttemplates.settings.PromptTemplatesSettingsListener
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class QuickUseDialog(
    private val project: Project,
    private val service: PromptTemplatesProjectService,
    private val invokingEditor: Editor?,
    onClosed: () -> Unit,
) : DialogWrapper(project, false, IdeModalityType.MODELESS) {
    private val scope = service.childScope("QuickUseDialog")
    private val settings = PromptTemplatesSettings.getInstance()
    private val invocation = service.invocation
    private var libraryRoot = settings.libraryRoot
    private var candidates = emptyList<QuickUseCandidate>()
    private var catalogDiagnostic: String? = null
    private var catalogJob: Job? = null
    private var searchJob: Job? = null
    private var selectionJob: Job? = null
    private var showingUse = false
    private var handingOff = false
    private var shownTemplate: StoredTemplate? = null
    private var form: DynamicVariableForm? = null
    private val search = JBTextField().apply {
        accessibleContext.accessibleName = "Search templates"
        emptyText.text = "Search templates"
    }
    private val matchModel = DefaultListModel<QuickUseCandidate>()
    private val matches = JBList(matchModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        accessibleContext.accessibleName = "Prompt template matches"
        cellRenderer = object : SimpleListCellRenderer<QuickUseCandidate>() {
            override fun customize(list: JList<out QuickUseCandidate>, value: QuickUseCandidate?, index: Int, selected: Boolean, hasFocus: Boolean) {
                val star = if (value?.search?.summary?.id?.value in settings.usage(libraryRoot).favourites) "★ " else ""
                text = value?.let { star + it.toString() }.orEmpty()
            }
        }
    }
    private val status = JBLabel("Loading templates…")
    private val favourite = JBCheckBox("Favourite").apply { mnemonic = KeyEvent.VK_F }
    private val cards = CardLayout()
    private val cardHost = JPanel(cards)
    private val useHost = JPanel(BorderLayout(JBUI.scale(8), JBUI.scale(8)))
    private val preview = EditorTextField("", project, PlainTextFileType.INSTANCE).apply {
        setOneLineMode(false)
        setViewer(true)
        accessibleContext.accessibleName = "Quick Use preview"
        addSettingsProvider { editor ->
            editor.settings.isUseSoftWraps = true
            configurePromptEditorScrollbars(editor.scrollPane)
        }
    }
    private val context = JBTextArea().apply {
        isEditable = false
        isOpaque = false
        lineWrap = true
        wrapStyleWord = true
        accessibleContext.accessibleName = "Quick Use context"
    }

    init {
        title = "Use Prompt Template"
        setOKButtonText("Use Template")
        okAction.putValue(Action.MNEMONIC_KEY, KeyEvent.VK_U)
        setCancelButtonText("Close")
        isOKActionEnabled = false
        init()
        search.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(event: DocumentEvent) = filterResults(preserveSelection = false)
        })
        search.addActionListener { selectCandidate() }
        for ((key, delta) in listOf(KeyEvent.VK_DOWN to 1, KeyEvent.VK_UP to -1)) {
            val action = "match-$delta"
            search.inputMap.put(KeyStroke.getKeyStroke(key, 0), action)
            search.actionMap.put(action, object : AbstractAction() {
                override fun actionPerformed(event: ActionEvent) {
                    if (matchModel.isEmpty) return
                    matches.selectedIndex = (matches.selectedIndex + delta).coerceIn(0, matchModel.size - 1)
                    matches.ensureIndexIsVisible(matches.selectedIndex)
                }
            })
        }
        matches.registerKeyboardAction({ selectCandidate() }, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), JComponent.WHEN_FOCUSED)
        matches.addListSelectionListener { if (!it.valueIsAdjusting && !showingUse) updateSelection() }
        favourite.addActionListener {
            selectedId()?.let { settings.toggleFavourite(it, libraryRoot) }
            filterResults()
        }
        Disposer.register(disposable) {
            scope.cancel()
            invocation.clearRememberedSource()
            onClosed()
            if (!handingOff) SwingUtilities.invokeLater {
                if (!project.isDisposed && invokingEditor?.isDisposed == false) invokingEditor.contentComponent.requestFocusInWindow()
            }
        }
        ApplicationManager.getApplication().messageBus.connect(disposable).subscribe(
            PromptTemplatesSettingsListener.TOPIC,
            PromptTemplatesSettingsListener { scope.launch(Dispatchers.EDT) { reloadCatalog() } },
        )
        scope.launch(Dispatchers.EDT) { service.libraryChanges.collect { reloadCatalog() } }
        scope.launch(Dispatchers.EDT) {
            invocation.state.collect { presentation ->
                if (showingUse) {
                    if (presentation == null) showPicker() else showInvocation(presentation)
                }
            }
        }
        reloadCatalog()
    }

    override fun createCenterPanel(): JComponent {
        val picker = JPanel(BorderLayout(JBUI.scale(8), JBUI.scale(8))).apply {
            add(search, BorderLayout.NORTH)
            add(JBScrollPane(matches), BorderLayout.CENTER)
            add(status, BorderLayout.SOUTH)
        }
        cardHost.add(picker, "picker")
        cardHost.add(useHost, "use")
        return JPanel(BorderLayout(JBUI.scale(8), JBUI.scale(8))).apply {
            preferredSize = JBUI.size(720, 520)
            add(cardHost, BorderLayout.CENTER)
            add(favourite, BorderLayout.SOUTH)
        }
    }

    override fun getPreferredFocusedComponent(): JComponent = search

    override fun doOKAction() {
        if (!showingUse) {
            selectCandidate()
            return
        }
        when (val result = invocation.copyRendered()) {
            DestinationResult.Success -> {
                setErrorText(null)
                status.text = "Prompt copied."
                close(OK_EXIT_CODE)
            }
            is DestinationResult.Failure -> {
                setErrorText(result.message)
                val missing = invocation.state.value?.invocation?.render?.diagnostics
                    ?.firstOrNull { it.severity == DiagnosticSeverity.ERROR }
                if (missing is TemplateDiagnostic.MissingRequiredValue) form?.focusVariable(missing.key)
            }
        }
    }

    private fun reloadCatalog() {
        val root = settings.libraryRoot
        if (root != libraryRoot) {
            libraryRoot = root
            candidates = emptyList()
            searchJob?.cancel()
            selectionJob?.cancel()
            matchModel.clear()
            status.text = "Loading templates…"
            showPicker()
        }
        catalogJob?.cancel()
        catalogJob = scope.launch(Dispatchers.IO) {
            val catalog = loadQuickUseCatalog(root)
            withContext(Dispatchers.EDT) {
                if (root != settings.libraryRoot) return@withContext
                candidates = catalog.candidates
                catalogDiagnostic = catalog.diagnostic
                filterResults()
            }
        }
    }

    private fun filterResults(preserveSelection: Boolean = true) {
        searchJob?.cancel()
        selectionJob?.cancel()
        val source = candidates
        val query = search.text
        val selected = matches.selectedValue?.search?.summary?.id?.takeIf { preserveSelection }
        val usage = settings.usage(libraryRoot)
        searchJob = scope.launch(Dispatchers.Default) {
            val bySearch = source.associateBy(QuickUseCandidate::search)
            val ranked = TemplateSearch.ranked(source.map(QuickUseCandidate::search), query, usage.favourites.toSet(), usage.recents)
            withContext(Dispatchers.EDT) {
                matchModel.clear()
                ranked.forEach { matchModel.addElement(bySearch.getValue(it)) }
                if (!matchModel.isEmpty) {
                    matches.selectedIndex = ranked.indexOfFirst { it.summary.id == selected }.takeIf { it >= 0 } ?: 0
                }
                status.text = catalogDiagnostic ?: if (matchModel.isEmpty) {
                    "No templates match. Try another search or add a template in the tool window."
                } else "Search by name, tags, folder, description or body."
                if (!showingUse) updateSelection()
            }
        }
    }

    private fun selectedId(): String? = if (showingUse) invocation.state.value?.invocation?.stored?.template?.id?.value
    else matches.selectedValue?.search?.summary?.id?.value

    private fun updateSelection() {
        val id = selectedId()
        favourite.isEnabled = id != null
        favourite.isSelected = id in settings.usage(libraryRoot).favourites
        if (!showingUse) isOKActionEnabled = id != null
    }

    private fun selectCandidate() {
        if (showingUse) return
        val candidate = matches.selectedValue ?: return
        val root = libraryRoot
        selectionJob?.cancel()
        isOKActionEnabled = false
        selectionJob = scope.launch(Dispatchers.IO) {
            val result = loadQuickUseSelection(root, candidate)
            withContext(Dispatchers.EDT) {
                if (root != settings.libraryRoot) return@withContext
                when (result) {
                    is RepositoryResult.Success -> {
                        showingUse = true
                        invocation.open(result.value)
                        invocation.state.value?.let(::showInvocation)
                    }
                    is RepositoryResult.Failure -> {
                        setErrorText(result.message)
                        updateSelection()
                    }
                }
            }
        }
    }

    private fun showPicker() {
        showingUse = false
        shownTemplate = null
        setOKButtonText("Use Template")
        okAction.putValue(Action.MNEMONIC_KEY, KeyEvent.VK_U)
        setErrorText(null)
        cards.show(cardHost, "picker")
        updateSelection()
        search.requestFocusInWindow()
    }

    private fun showInvocation(presentation: InvocationPresentation) {
        val current = presentation.invocation
        val newForm = shownTemplate != current.stored
        if (newForm) {
            shownTemplate = current.stored
            val inputs = referencedUserVariables(current.stored.template)
            form = DynamicVariableForm(inputs, emptyMap(), current.values, invocation::setValue)
            val formScroll = JBScrollPane(form).apply { horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER }
            val previewPanel = JPanel(BorderLayout(JBUI.scale(8), JBUI.scale(8))).apply {
                add(context, BorderLayout.NORTH)
                add(preview, BorderLayout.CENTER)
            }
            useHost.removeAll()
            useHost.add(JBLabel(current.stored.template.metadata.name), BorderLayout.NORTH)
            useHost.add(createUseViewContent(inputs.isNotEmpty(), formScroll, previewPanel), BorderLayout.CENTER)
            useHost.add(ResponsiveActionsPanel().apply {
                add(JButton("Add Context…").apply { addActionListener { service.manageAttachments() } })
                add(JButton("Choose Another").apply { addActionListener { showPicker(); filterResults() } })
                add(JButton("Open in Tool Window").apply {
                    addActionListener {
                        service.show { panel ->
                            if (panel.continueInvocation()) {
                                handingOff = true
                                close(OK_EXIT_CODE)
                            }
                        }
                    }
                })
            }, BorderLayout.SOUTH)
            useHost.revalidate()
        }
        form?.updateValues(current.values)
        preview.text = current.render.renderedText
        context.text = current.referencedContext.joinToString("\n") { key ->
            val value = current.context[key]
            "$key: ${value?.errorMessage ?: value?.status?.name.orEmpty()}"
        }
        setErrorText(presentation.deliveryProblem)
        setOKButtonText("Copy Prompt")
        okAction.putValue(Action.MNEMONIC_KEY, KeyEvent.VK_C)
        isOKActionEnabled = !presentation.capturing
        updateSelection()
        cards.show(cardHost, "use")
        if (newForm) SwingUtilities.invokeLater {
            if (showingUse) {
                val first = referencedUserVariables(current.stored.template).firstOrNull()
                if (first != null) form?.focusVariable(first.key) else getButton(okAction)?.requestFocusInWindow()
            }
        }
    }
}
