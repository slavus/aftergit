package net.aftergit.merge.core.merge;

/**
 * Part of a file a change lies in: the import section of a Java file (spec §5.3), or everything else. An IMPORTS
 * change may also cover the blank lines around the section (when a side has no imports).
 */
public enum Region { BODY, IMPORTS }
