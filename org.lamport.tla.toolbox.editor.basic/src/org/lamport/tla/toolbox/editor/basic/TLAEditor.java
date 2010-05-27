package org.lamport.tla.toolbox.editor.basic;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarkerDelta;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.action.IContributionItem;
import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.text.Position;
import org.eclipse.jface.text.Region;
import org.eclipse.jface.text.TextViewer;
import org.eclipse.jface.text.hyperlink.IHyperlink;
import org.eclipse.jface.text.hyperlink.IHyperlinkDetector;
import org.eclipse.jface.text.source.Annotation;
import org.eclipse.jface.text.source.IAnnotationModel;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.jface.text.source.IVerticalRuler;
import org.eclipse.jface.text.source.projection.ProjectionAnnotation;
import org.eclipse.jface.text.source.projection.ProjectionAnnotationModel;
import org.eclipse.jface.text.source.projection.ProjectionSupport;
import org.eclipse.jface.text.source.projection.ProjectionViewer;
import org.eclipse.jface.viewers.ISelectionProvider;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorSite;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.IWorkbenchActionConstants;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.contexts.IContextActivation;
import org.eclipse.ui.contexts.IContextService;
import org.eclipse.ui.editors.text.EditorsUI;
import org.eclipse.ui.editors.text.TextEditor;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.part.FileEditorInput;
import org.eclipse.ui.texteditor.ChainedPreferenceStore;
import org.eclipse.ui.texteditor.IDocumentProvider;
import org.eclipse.ui.texteditor.ITextEditorActionConstants;
import org.eclipse.ui.texteditor.ITextEditorActionDefinitionIds;
import org.eclipse.ui.texteditor.ResourceAction;
import org.eclipse.ui.texteditor.TextOperationAction;
import org.lamport.tla.toolbox.Activator;
import org.lamport.tla.toolbox.editor.basic.actions.ProofFoldAction;
import org.lamport.tla.toolbox.editor.basic.actions.ToggleCommentAction;
import org.lamport.tla.toolbox.editor.basic.proof.IProofFoldCommandIds;
import org.lamport.tla.toolbox.editor.basic.proof.TLAProofFoldingStructureProvider;
import org.lamport.tla.toolbox.editor.basic.util.ElementStateAdapter;
import org.lamport.tla.toolbox.tool.ToolboxHandle;
import org.lamport.tla.toolbox.util.ResourceHelper;
import org.lamport.tla.toolbox.util.StringHelper;
import org.lamport.tla.toolbox.util.UIHelper;

/**
 * Basic editor for TLA+
 *
 * @author Simon Zambrovski
 * @version $Id$
 */
public class TLAEditor extends TextEditor
{
    public static final String ID = "org.lamport.tla.toolbox.editor.basic.TLAEditor";
    private IContextService contextService = null;
    private IContextActivation contextActivation = null;

    // projection support is required for folding
    private ProjectionSupport projectionSupport;
    // image for root module icon
    private Image rootImage = TLAEditorActivator.imageDescriptorFromPlugin(TLAEditorActivator.PLUGIN_ID,
            "/icons/root_file.gif").createImage();

    // currently installed annotations
    private Annotation[] oldAnnotations;
    // annotation model
    private ProjectionAnnotationModel annotationModel;
    // proof structure provider
    private TLAProofFoldingStructureProvider proofStructureProvider;
    /**
     * The annotation model for adding visual elements
     * such as colored annotation.
     */
    private IAnnotationModel visualAnnotationModel;
    /**
     * Listener to TLAPM output for adding status
     * coloring to proofs.
     * 
     * Commented out by DR. I think the coloring
     * will be done using markers in the prover
     * ui plug-in.
     */
    // private TLAPMColoringOutputListener tlapmColoring;
    /**
     * Listens to resource changes to the module.
     * 
     * In particular, reacts to prover running marker
     * begin changed on the module.
     * 
     * Calls {@link TLAEditor#refresh()} when this happens.
     */
    private IResourceChangeListener moduleFileChangeListener;

    /**
     * Constructor
     */
    public TLAEditor()
    {

        super();

        // help id
        setHelpContextId("org.lamport.tla.toolbox.editor.basic.main_editor_window");
    }

    /**
     * Register the element state listener to react on the changes to the state (saved, dirty:=not saved) 
     */
    protected void setDocumentProvider(IEditorInput input)
    {
        super.setDocumentProvider(input);

        IDocumentProvider provider = getDocumentProvider();
        if (provider != null)
        {
            provider.addElementStateListener(new ElementStateAdapter() {
                public void elementDirtyStateChanged(Object element, boolean isDirty)
                {
                    if (isDirty)
                    {
                        contextService.deactivateContext(contextActivation);
                    } else
                    {
                        contextActivation = contextService.activateContext("toolbox.contexts.cleaneditor");
                    }
                }
            });
        }
    }

    /*
     * (non-Javadoc)
     * @see org.eclipse.ui.texteditor.AbstractTextEditor#init(org.eclipse.ui.IEditorSite, org.eclipse.ui.IEditorInput)
     */
    public void init(IEditorSite site, IEditorInput input) throws PartInitException
    {

        super.init(site, input);

        // chain preference stores (our own, and the one for standard editor settings)
        IPreferenceStore preferenceStore = new ChainedPreferenceStore(new IPreferenceStore[] {
                TLAEditorActivator.getDefault().getPreferenceStore(), EditorsUI.getPreferenceStore() });

        // set source viewer configuration
        setSourceViewerConfiguration(new TLASourceViewerConfiguration(preferenceStore, this));

        // set preference store
        setPreferenceStore(preferenceStore);

        // setup the content description and image of spec root
        if (input instanceof FileEditorInput)
        {
            FileEditorInput finput = (FileEditorInput) input;
            if (finput != null)
            {
                IPath path = finput.getPath();
                setContentDescription(path.toString());

                if (ResourceHelper.isRoot(finput.getFile()))
                {
                    setTitleImage(rootImage);
                }
            }
        }
        // grab context service and activate the context on editor load
        this.contextService = (IContextService) getSite().getService(IContextService.class);
        this.contextActivation = contextService.activateContext("toolbox.contexts.cleaneditor");

        /*
         * This resource change listener listens to changes
         * to markers. If it finds a change to the prover running
         * marker on the module in this editor, then it refreshes
         * the editor. See method refresh().
         */
        this.moduleFileChangeListener = new IResourceChangeListener() {

            public void resourceChanged(IResourceChangeEvent event)
            {
                IMarkerDelta[] markerChanges = event.findMarkerDeltas(""/*ProverHelper.PROVER_RUNNING_MARKER*/, false);

                for (int i = 0; i < markerChanges.length; i++)
                {
                    if (markerChanges[i].getResource().equals(((IFileEditorInput) getEditorInput()).getFile()))
                    {
                        UIHelper.runUISync(new Runnable() {

                            public void run()
                            {
                                refresh();
                            }
                        });
                    }
                }
            }
        };

        ResourcesPlugin.getWorkspace().addResourceChangeListener(moduleFileChangeListener);

    }

    /*
     * @see org.eclipse.ui.texteditor.ExtendedTextEditor#createSourceViewer(org.eclipse.swt.widgets.Composite, org.eclipse.jface.text.source.IVerticalRuler, int)
     */
    protected ISourceViewer createSourceViewer(Composite parent, IVerticalRuler ruler, int styles)
    {
        ProjectionViewer viewer = new ProjectionViewer(parent, ruler, getOverviewRuler(), isOverviewRulerVisible(),
                styles);
        // ensure decoration support has been created and configured.
        // @see org.eclipse.ui.texteditor.ExtendedTextEditor#createSourceViewer
        getSourceViewerDecorationSupport(viewer);
        return viewer;
    }

    public void createPartControl(Composite parent)
    {
        super.createPartControl(parent);
        /*
         * Add projection support (e.G. for folding) 
         */
        ProjectionViewer viewer = (ProjectionViewer) getSourceViewer();
        projectionSupport = new ProjectionSupport(viewer, getAnnotationAccess(), getSharedColors());
        projectionSupport.addSummarizableAnnotationType("org.eclipse.ui.workbench.texteditor.error"); //$NON-NLS-1$
        projectionSupport.addSummarizableAnnotationType("org.eclipse.ui.workbench.texteditor.warning"); //$NON-NLS-1$
        projectionSupport.install();
        viewer.doOperation(ProjectionViewer.TOGGLE);

        // model for adding projections (folds)
        this.annotationModel = viewer.getProjectionAnnotationModel();
        // model for adding other visual annotations, such as coloring.
        this.visualAnnotationModel = viewer.getAnnotationModel();

        // this must be instantiated after annotationModel so that it does
        // not call methods that use annotation model when the model is still null
        this.proofStructureProvider = new TLAProofFoldingStructureProvider(this);

        // tlapmColoring = new TLAPMColoringOutputListener(this);

    }

    /**
     * Create editor actions
     */
    protected void createActions()
    {
        super.createActions();

        // content assist proposal action
        IAction a = new TextOperationAction(TLAEditorMessages.getResourceBundle(),
                "ContentAssistProposal.", this, ISourceViewer.CONTENTASSIST_PROPOSALS); //$NON-NLS-1$
        a.setActionDefinitionId(ITextEditorActionDefinitionIds.CONTENT_ASSIST_PROPOSALS);
        setAction("ContentAssistProposal", a); //$NON-NLS-1$

        // content assist tip action
        a = new TextOperationAction(TLAEditorMessages.getResourceBundle(),
                "ContentAssistTip.", this, ISourceViewer.CONTENTASSIST_CONTEXT_INFORMATION); //$NON-NLS-1$
        a.setActionDefinitionId(ITextEditorActionDefinitionIds.CONTENT_ASSIST_CONTEXT_INFORMATION);
        setAction("ContentAssistTip", a); //$NON-NLS-1$

        // define folding region action
        /*      a = new DefineFoldingRegionAction(TLAEditorMessages.getResourceBundle(), "DefineFoldingRegion.", this); //$NON-NLS-1$
                setAction("DefineFoldingRegion", a); //$NON-NLS-1$
                markAsStateDependentAction("DefineFoldingRegion", true); //$NON-NLS-1$
                markAsSelectionDependentAction("DefineFoldingRegion", true); //$NON-NLS-1$
        */

        // toggle comment
        a = new ToggleCommentAction(TLAEditorMessages.getResourceBundle(), "ToggleComment.", this); //$NON-NLS-1$
        a.setActionDefinitionId(TLAEditorActivator.PLUGIN_ID + ".ToggleCommentAction");
        setAction("ToggleComment", a); //$NON-NLS-1$
        markAsStateDependentAction("ToggleComment", true); //$NON-NLS-1$
        markAsSelectionDependentAction("ToggleComment", true);
        ((ToggleCommentAction) a).configure(getSourceViewer(), getSourceViewerConfiguration());

        // format action (only if formater available)
        if (getSourceViewerConfiguration().getContentFormatter(getSourceViewer()) != null)
        {
            a = new TextOperationAction(TLAEditorMessages.getResourceBundle(), "Format.", this, ISourceViewer.FORMAT); //$NON-NLS-1$
            a.setActionDefinitionId(TLAEditorActivator.PLUGIN_ID + ".FormatAction");
            setAction("Format", a); //$NON-NLS-1$
            markAsStateDependentAction("Format", true); //$NON-NLS-1$
            markAsSelectionDependentAction("Format", true); //$NON-NLS-1$
        }

        // createProofFoldAction(IProofFoldCommandIds.FOCUS_ON_STEP, "FoldUnusable.", "FoldUnusable");
        // createProofFoldAction(IProofFoldCommandIds.FOLD_ALL_PROOFS, "FoldAllProofs.", "FoldAllProofs");
        // createProofFoldAction(IProofFoldCommandIds.EXPAND_ALL_PROOFS, "ExpandAllProofs.", "ExpandAllProofs");
        // createProofFoldAction(IProofFoldCommandIds.EXPAND_SUBTREE, "ExpandSubtree.", "ExpandSubtree");
        // createProofFoldAction(IProofFoldCommandIds.COLLAPSE_SUBTREE, "CollapseSubtree.", "CollapseSubtree");
        // createProofFoldAction(IProofFoldCommandIds.SHOW_IMMEDIATE, "ShowImmediate.", "ShowImmediate");

        //a = new ExampleEditorAction(TLAEditorMessages.getResourceBundle(), "Example.", this, getStatusLineManager()); //$NON-NLS-1$
        // a.setActionDefinitionId("org.lamport.tla.toolbox.editor.basic.TestEditorCommand");
        // setAction("ToggleComment", a);
    }

    /**
     * Creates a proof folding action for the given commandId (the action will be executed when
     * that command is called). Its unclear what the purpose of prefix is (for an explanation,
     * see the explanation of the prefix argument for
     * {@link ResourceAction#ResourceAction(java.util.ResourceBundle, String)}. Use the examples
     * in createActions() for what the prefix should be.
     * 
     * The actionId should be a String that no other actions in this class use.
     * 
     * @param commandId
     * @param prefix
     * @param actionId
     */
    private void createProofFoldAction(String commandId, String prefix, String actionId)
    {
        IAction a = new ProofFoldAction(TLAEditorMessages.getResourceBundle(), prefix, this);
        a.setActionDefinitionId(commandId);
        setAction(actionId, a);
        // markAsStateDependentAction(actionId, true);
        // markAsSelectionDependentAction(actionId, true);
    }

    /**
     * Called when the context menu is about to show.
     * 
     * We use this to remove unwanted items from the menu
     * that eclipse plug-ins contribute.
     */
    protected void editorContextMenuAboutToShow(IMenuManager menuManager)
    {
        super.editorContextMenuAboutToShow(menuManager);
        // The following adds an extra section for the fold commands.
        // First, try to find the additions group.
        IContributionItem additions = menuManager.find(IWorkbenchActionConstants.MB_ADDITIONS);
        if (additions != null)
        {
            // insert fold commands after additions if found
            menuManager.insertAfter(IWorkbenchActionConstants.MB_ADDITIONS, new Separator("foldCommands"));
        } else
        {
            // else just put fold commands at the end
            menuManager.add(new Separator("foldCommands"));
        }

        /*
         * The following removes unwanted preference items.
         * 
         * The calls to the super class implementations of this method
         * add menu items that we dont want, so after they are added,
         * we remove them.
         */
        menuManager.remove(ITextEditorActionConstants.SHIFT_RIGHT);
        menuManager.remove(ITextEditorActionConstants.SHIFT_LEFT);

        /*
         * The following is a bit of a hack to remove
         * the "Show In" submenu that is contributed by
         * AbstractDecoratedTextEditor in its implementation
         * of this method. It is contributed without
         * an id, so we have to check all items in the menu
         * to check if a submenu contains an item with
         * the id viewShowIn. Then we remove this submenu.
         */
        IContributionItem[] items = menuManager.getItems();
        for (int i = 0; i < items.length; i++)
        {
            if (items[i] instanceof MenuManager)
            {
                MenuManager subMenu = (MenuManager) items[i];
                if (subMenu.find("viewsShowIn") != null)
                {
                    menuManager.remove(subMenu);
                    break;
                }
            }
        }

    }

    /*
     * We override this method to add information about who modified the file when. 
     * @see org.eclipse.ui.texteditor.AbstractTextEditor#doSave(org.eclipse.core.runtime.IProgressMonitor)
     */
    public void doSave(IProgressMonitor progressMonitor)
    {
        IDocument doc = this.getDocumentProvider().getDocument(this.getEditorInput());
        String text = doc.get();

        // Set historyStart to the offset at the start of the
        // modification history section, if there is one. And if there is,
        // we add the modification date and user.
        int historyStart = text.indexOf(ResourceHelper.modificationHistory);

        if (historyStart > -1)
        {

            // Set newEntryStart to the point at which the new modification
            // information is to be inserted.
            int newEntryStart = historyStart + ResourceHelper.modificationHistory.length();

            String user = System.getProperty("user.name");
            String searchString = ResourceHelper.modifiedBy + user;
            int searchStringLength = searchString.length();

            // Need to remove existing modification entry for user
            // if there is one. The following while loop sets found to true
            // iff there is an existing entry and, if so, sets nextEntry to
            // its starting offset and endOfLine to the offset at its end.
            boolean found = false;
            // In the loop, nextEntry is set to the start of the next
            // Modified entry. The next one is found by searching from
            // one character to the right of the current entry. To start
            // the loop, we initialize it to one char to the left of
            // newEntryStart, which could be pointing to the first
            // Modified entry.
            int nextEntry = newEntryStart - 1;
            int endOfLine = -1; // initialization needed to make compiler happy.
            label: while (!found)
            {
                nextEntry = text.indexOf(ResourceHelper.lastModified, nextEntry + 1);
                // It we don't find an entry, we eventually exit by
                // nextEntry becoming -1.
                if (nextEntry < 0)
                {
                    break label;
                }
                endOfLine = text.indexOf(StringHelper.newline, nextEntry + 1);
                if (endOfLine < 0)
                {
                    endOfLine = text.length();
                }
                // We want this to work even if an extra space is added at the end
                // of the user name. So, we set realEOL to the offset just to the
                // right of the last non-space character to the left of endOfLine,
                // and look for the user name ending there.
                int realEOL = endOfLine;
                while (text.charAt(realEOL - 1) == ' ')
                {
                    realEOL--;
                }

                if ((nextEntry + searchStringLength < realEOL)
                        && (searchString.equals(text.substring(realEOL - searchStringLength, realEOL))))
                {
                    found = true;
                }
            } // end while

            // Need to put the rest of the replacement code into
            // a try / catch for BadLocationExceptions.
            try
            {
                // remove old entry, if there is one.
                if (found)
                {
                    doc.replace(nextEntry, endOfLine - nextEntry, "");
                }
                doc.replace(newEntryStart, 0, ResourceHelper.lastModified + (new Date()) + searchString);
            } catch (BadLocationException e)
            { // System.out.println("Exception.");

            }

        } // end if (historyStart > -1)

        super.doSave(progressMonitor);
    }

    /**
     * Update the annotation structure in the editor.
     * 
     * This is only currently used by comment
     * folding and should be removed because it
     * is incorrect.
     * 
     * @param positions
     * @deprecated
     */
    public void updateFoldingStructure(ArrayList positions)
    {

        Annotation[] annotations = new Annotation[positions.size()];

        // this will hold the new annotations along
        // with their corresponding positions
        HashMap newAnnotations = new HashMap();

        for (int i = 0; i < positions.size(); i++)
        {
            ProjectionAnnotation annotation = new ProjectionAnnotation();
            newAnnotations.put(annotation, positions.get(i));
            annotations[i] = annotation;
        }
        // If this method is called too early, then annotationModel
        // can be null. This should obviously be addressed.
        this.annotationModel.modifyAnnotations(oldAnnotations, newAnnotations, null);
        oldAnnotations = annotations;
    }

    /**
     * Calls {@link ProjectionAnnotationModel#modifyAnnotations(Annotation[], Map, Annotation[])} with the
     * arguments.
     * 
     * Note that in the map additions, the keys should be instances of {@link ProjectionAnnotation} and the values
     * should be instances of the corresponding {@link Position}.
     * 
     * This method is for changing the projection annotations. For changing other visual annotations, use
     * {@link TLAEditor#modifyRegularAnnotations(Annotation[], Map, Annotation[])}.
     * 
     * @param deletions
     * @param additions
     * @param modifications
     */
    public void modifyProjectionAnnotations(Annotation[] deletions, Map additions, Annotation[] modifications)
    {
        this.annotationModel.modifyAnnotations(deletions, additions, modifications);
    }

    /**
     * Adds, deletes, and modifies "regular" annotations for this editor. These are
     * visual annotations, such as proof coloring, that are not projection annotations.
     * To make changes to the projection annotations, {@link TLAEditor#modifyProjectionAnnotations(Annotation[], Map, Annotation[])}
     * should be used.
     * 
     * @param deletions
     * @param additions
     * @param modifications
     */
    public void modifyRegularAnnotations(Annotation[] deletions, Map additions, Annotation[] modifications)
    {
        if (visualAnnotationModel == null)
        {
            Activator.logDebug("Visual annotation model is null and annotations are being added. This is a bug.");
            return;
        }

        // delete annotations
        if (deletions != null)
        {
            for (int i = 0; i < deletions.length; i++)
            {
                visualAnnotationModel.removeAnnotation(deletions[i]);
            }
        }

        /*
         * Modify annotations.
         * 
         * For each annotation to be modified:
         * 
         * Get the position of the annotation.
         * Remove the annotation.
         * Add the annotation again.
         */
        if (modifications != null)
        {
            for (int i = 0; i < modifications.length; i++)
            {
                Position position = visualAnnotationModel.getPosition(modifications[i]);
                visualAnnotationModel.removeAnnotation(modifications[i]);
                visualAnnotationModel.addAnnotation(modifications[i], position);
            }
        }

        // add new annotations
        if (additions != null)
        {
            for (Iterator it = additions.entrySet().iterator(); it.hasNext();)
            {
                Map.Entry entry = (Entry) it.next();
                Annotation annotation = (Annotation) entry.getKey();
                Position position = (Position) entry.getValue();
                visualAnnotationModel.addAnnotation(annotation, position);
            }
        }
    }

    /**
     * SaveAs support
     * 
     * Now that this editor is part of the multi-page editor
     * {@link TLAEditorAndPDFViewer} this method should not be called.
     * Instead, the method {@link TLAEditorAndPDFViewer#doSaveAs()} should
     * be called when the user selects Save As.
     */
    protected void performSaveAs(IProgressMonitor progressMonitor)
    {

        IFile file = ((FileEditorInput) getEditorInput()).getFile();
        Shell shell = UIHelper.getShellProvider().getShell();

        // TODO fix this?
        IPath specRootPrefix = new Path(ResourceHelper.getParentDirName(ToolboxHandle.getRootModule()));

        FileDialog saveAsDialog = null;
        while (true)
        {
            // construct the dialog
            // should this be replaced by a dialog showing the logical view of the FS?
            saveAsDialog = new FileDialog(shell, SWT.SAVE);
            saveAsDialog.setOverwrite(true);
            saveAsDialog.setText("Select the new filename...");
            saveAsDialog.setFilterExtensions(new String[] { "*.tla" });
            saveAsDialog.setFilterNames(new String[] { "TLA+ Files" });
            saveAsDialog.setFilterPath(file.getLocation().toOSString());
            String result = saveAsDialog.open();
            saveAsDialog = null;
            // no cancellation
            if (result != null)
            {
                IPath newPath = new Path(result);
                // check whether the file is in the same directory as the root module
                if (!specRootPrefix.isPrefixOf(newPath))
                {
                    MessageDialog.openError(shell, "Wrong location selected",
                            "The provided filename point to the same directory as the specification root file");
                    continue;
                }

                // fix the extension
                if (!"tla".equals(newPath.getFileExtension()))
                {
                    newPath = newPath.addFileExtension("tla");
                }

                File newFile = newPath.toFile();
                if (newFile.exists())
                {
                    boolean confirmOverwrite = MessageDialog.openQuestion(shell, "Overwrite file?",
                            "The provided filename already exists. The existing file will be overwritten.\nDo you want to overwrite the file "
                                    + newPath.toOSString() + "?");
                    if (!confirmOverwrite)
                    {
                        continue;
                    }

                    // overwriting existing file file exists
                } else
                {
                    // file does not exist
                    // create the empty file
                    boolean newFileCreated = false;
                    try
                    {
                        newFileCreated = newFile.createNewFile();
                    } catch (IOException e)
                    {
                        // do nothing, since we react on the newFileCreated being false
                    }
                    if (!newFileCreated)
                    {
                        MessageDialog.openError(shell, "Error saving a file", "File could not be created");
                        break;
                    }
                }

                // create a link to a file
                IFile newResource = ResourceHelper.getLinkedFile(file.getProject(), newPath.toOSString());

                // new editor input
                IEditorInput newInput = new FileEditorInput(newResource);

                // System.out.println("TODO: Save " + file.getLocation().toOSString() + " as " + newPath);

                // get the document provider
                IDocumentProvider provider = getDocumentProvider();
                boolean saveAsSuccess = false;
                try
                {
                    // notify
                    provider.aboutToChange(newInput);
                    // perform the save
                    provider.saveDocument(progressMonitor, newInput, provider.getDocument(getEditorInput()), true);
                    saveAsSuccess = true;
                } catch (CoreException x)
                {
                    MessageDialog.openError(shell, "Error saving a file", "File could not be created");
                } finally
                {
                    // notify
                    provider.changed(newInput);
                    if (saveAsSuccess)
                    {
                        // change the input
                        // alternatively, open another editor with the new resource?
                        setInput(newInput);
                    }
                }
                // break out on save
                break;
            } else
            {
                // break out on cancel of the dialog
                break;
            }
        }
    }

    public Object getAdapter(Class required)
    {
        /* adapt to projection support */
        if (projectionSupport != null)
        {
            Object adapter = projectionSupport.getAdapter(getSourceViewer(), required);
            if (adapter != null)
                return adapter;
        }

        if (ISelectionProvider.class.equals(required))
        {
            return getSelectionProvider();
        }

        return super.getAdapter(required);
    }

    public void dispose()
    {
        // tlapmColoring.dispose();
        proofStructureProvider.dispose();
        rootImage.dispose();
        ResourcesPlugin.getWorkspace().removeResourceChangeListener(moduleFileChangeListener);
        super.dispose();
    }

    public TextViewer getViewer()
    {
        return (TextViewer) getSourceViewer();
    }

    public void setStatusMessage(String message)
    {
        getStatusLineManager().setMessage(message);
    }

    /**
     * Runs the fold operation represented by commandId. This
     * should be an id from {@link IProofFoldCommandIds}.
     * @param commandId
     */
    public void runFoldOperation(String commandId)
    {
        // the current selection
        if (getSelectionProvider().getSelection() instanceof ITextSelection)
        {
            ITextSelection selection = (ITextSelection) getSelectionProvider().getSelection();

            proofStructureProvider.runFoldOperation(commandId, selection);
        }
    }

    public TLAProofFoldingStructureProvider getProofStructureProvider()
    {
        return proofStructureProvider;
    }

    /**
     * Refreshes the editor.
     * 
     * This currently just involves checking if the prover
     * is running on the module. If it is, the editor
     * is set to be read only. If it isn't, the editor
     * is set to be editable.
     */
    private void refresh()
    {
        getSourceViewer()
                .setEditable(true/*!ProverHelper.isProverRunning(((FileEditorInput) getEditorInput()).getFile())*/);
    }

    /**
     * The class is located here, because editor does not expose the source viewer (method protected)
     */
    public static final class OpenDeclarationHandler extends AbstractHandler
    {
        public OpenDeclarationHandler()
        {
        }

        public Object execute(ExecutionEvent event) throws ExecutionException
        {

            TLAEditorAndPDFViewer editor = (TLAEditorAndPDFViewer) HandlerUtil.getActiveEditor(event);
            ISourceViewer internalSourceViewer = editor.getTLAEditor().getSourceViewer();

            ITextSelection selection = (ITextSelection) editor.getTLAEditor().getSelectionProvider().getSelection();
            IRegion region = new Region(selection.getOffset(), selection.getLength());

            // get the detectors
            IHyperlinkDetector[] hyperlinkDetectors = editor.getTLAEditor().getSourceViewerConfiguration()
                    .getHyperlinkDetectors(internalSourceViewer);
            if (hyperlinkDetectors != null)
            {
                for (int i = 0; i < hyperlinkDetectors.length; i++)
                {
                    // detect
                    IHyperlink[] hyperlinks = hyperlinkDetectors[i].detectHyperlinks(internalSourceViewer, region,
                            false);
                    if (hyperlinks != null && hyperlinks.length > 0)
                    {
                        // open
                        IHyperlink hyperlink = hyperlinks[0];
                        hyperlink.open();
                        break;
                    }
                }
            }
            return null;
        }
    }

}
