package net.aftergit.merge.ui;

import org.eclipse.jface.text.ITextHover;
import org.eclipse.jface.text.IUndoManager;

/** What the resolver lends a pane viewer: its hover and (result only) its undo; {@code undo} may be null. */
record PaneServices(ITextHover hover, IUndoManager undo) {}
