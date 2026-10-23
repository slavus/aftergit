package net.aftergit.merge.jdt;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.eclipse.jface.text.ISynchronizable;
import org.eclipse.jface.text.Position;
import org.eclipse.jface.text.source.Annotation;
import org.eclipse.jface.text.source.IAnnotationModel;
import org.eclipse.jface.text.source.IAnnotationModelListener;
import org.eclipse.ui.texteditor.DefaultMarkerAnnotationAccess;
import org.eclipse.ui.texteditor.MarkerAnnotation;

/** Errors and warnings of an annotation model as {@link Problem}s, with change notification. */
final class ProblemTracker implements IAnnotationModelListener {
  private final IAnnotationModel model;
  private final DefaultMarkerAnnotationAccess access = new DefaultMarkerAnnotationAccess();
  private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

  ProblemTracker(IAnnotationModel model) {
    this.model = model;
    model.addAnnotationModelListener(this);
  }

  void addListener(Runnable listener) {
    listeners.add(listener);
  }

  @Override
  public void modelChanged(IAnnotationModel changed) {
    for (Runnable r : listeners) {
      r.run();
    }
  }

  List<Problem> problems() {
    List<Problem> out = new ArrayList<>();
    Object lock = model instanceof ISynchronizable s && s.getLockObject() != null ? s.getLockObject() : model;
    synchronized (lock) {
      Iterator<Annotation> it = model.getAnnotationIterator();
      while (it.hasNext()) {
        Annotation a = it.next();
        // builder markers describe the file on disk (with git's markers), not the buffer being merged
        if (a.isMarkedDeleted() || a instanceof MarkerAnnotation) {
          continue;
        }
        boolean error = access.isSubtype(a.getType(), AnnotationColumns.ERROR);
        if (!error && !access.isSubtype(a.getType(), AnnotationColumns.WARNING)) {
          continue;
        }
        Position p = model.getPosition(a);
        if (p != null && !p.isDeleted()) {
          out.add(new Problem(p.getOffset(), p.getLength(), error, a.getText()));
        }
      }
    }
    out.sort(Comparator.comparingInt(Problem::offset));
    return List.copyOf(out);
  }

  void dispose() {
    model.removeAnnotationModelListener(this);
    listeners.clear();
  }
}
