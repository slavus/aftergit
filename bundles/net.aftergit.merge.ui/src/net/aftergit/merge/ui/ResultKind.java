package net.aftergit.merge.ui;

/** What edits the result: plain text, Java colors and errors only, or the full Java editor (spec §7.2). */
enum ResultKind { PLAIN, JAVA_DEGRADED, JAVA_EDITOR }
