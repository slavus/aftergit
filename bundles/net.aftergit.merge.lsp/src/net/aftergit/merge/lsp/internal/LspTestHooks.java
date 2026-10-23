package net.aftergit.merge.lsp.internal;

import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import org.eclipse.tm4e.core.grammar.IGrammar;

/** Hooks for the tests of AfterGit; not API. */
public final class LspTestHooks {
  /** The system property naming the servers AfterGit may use: comma-separated prefixes of lsp4e server ids. */
  public static final String SERVERS_PROPERTY = "aftergit.lsp.servers";

  private static volatile Function<String, IGrammar> grammarLookup;
  private static volatile Runnable reconcilerHook;
  private static volatile Predicate<String> serverFilter;
  private static volatile long[] sideTimes;

  private LspTestHooks() {}

  /** Replaces the look-up of a grammar by file name (null: Eclipse's registry); a throwing lookup is a broken one. */
  public static void setGrammarLookup(Function<String, IGrammar> lookup) {
    grammarLookup = lookup;
  }

  public static Function<String, IGrammar> grammarLookup() {
    return grammarLookup;
  }

  /**
   * Runs {@code hook} as each pane's reconciler is made (null: nothing); a throwing hook is a broken TextMate theme or
   * a tm4e that fails while the panes are built.
   */
  public static void setReconcilerHook(Runnable hook) {
    reconcilerHook = hook;
  }

  public static Runnable reconcilerHook() {
    return reconcilerHook;
  }

  /** Replaces the choice of the language servers AfterGit uses, by lsp4e server id (null: {@link #serverFilter()}). */
  public static void setServerFilter(Predicate<String> filter) {
    serverFilter = filter;
  }

  /**
   * The servers AfterGit may use: the filter of {@link #setServerFilter}, else those whose id starts with a prefix of
   * the system property {@value #SERVERS_PROPERTY} (the UI tests: only the stand-in, so the servers of Wild Web
   * Developer never start), else all.
   */
  public static Predicate<String> serverFilter() {
    Predicate<String> f = serverFilter;
    if (f != null) {
      return f;
    }
    String property = System.getProperty(SERVERS_PROPERTY);
    if (property == null || property.isBlank()) {
      return id -> true;
    }
    List<String> prefixes = Arrays.stream(property.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    return id -> id != null && prefixes.stream().anyMatch(id::startsWith);
  }

  /**
   * Replaces the times of the side checks in milliseconds: the start of a server, the wait for an answer, the quiet
   * time after the last answer, the idle time before an unused server stops, and the wait for the first answer of an
   * instance; a negative or missing value keeps the default. Null resets all five.
   */
  public static void setSideTimes(long[] startCheckQuietIdleFirst) {
    sideTimes = startCheckQuietIdleFirst == null ? null : startCheckQuietIdleFirst.clone();
  }

  /**
   * The time {@code index} of {@link #setSideTimes} (0 start, 1 check, 2 quiet, 3 idle, 4 first check), or
   * {@code fallback}.
   */
  public static long sideTime(int index, long fallback) {
    long[] t = sideTimes;
    return t == null || index >= t.length || t[index] < 0 ? fallback : t[index];
  }
}
