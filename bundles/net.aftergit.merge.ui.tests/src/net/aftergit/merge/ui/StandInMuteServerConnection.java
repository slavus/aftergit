package net.aftergit.merge.ui;

/**
 * The two other stand-in servers for "*.agpair" files ({@link #ID} and {@link #ID2} in fragment.xml), next to
 * {@link StandInLanguageServer}: the same rules, but they never answer AfterGit's virtual documents, as a server
 * that checks only files on disk.
 */
public final class StandInMuteServerConnection extends StandInServerConnection {
  static final String ID = "net.aftergit.merge.ui.tests.standin.mute";
  static final String ID2 = "net.aftergit.merge.ui.tests.standin.mute2";

  @Override
  boolean muteVirtual() {
    return true;
  }
}
